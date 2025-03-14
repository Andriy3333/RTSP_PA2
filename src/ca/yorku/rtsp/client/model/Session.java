/**
 * Author: Jonatan Schroeder
 * Updated: March 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.model;

import ca.yorku.rtsp.client.exception.RTSPException;
import ca.yorku.rtsp.client.net.RTSPConnection;

import java.util.*;
import java.util.concurrent.*;

public class Session {

    private Set<SessionListener> sessionListeners = new HashSet<>();
    private RTSPConnection rtspConnection;
    private String videoName = null;

    // Frame buffer (Sorted by sequence number)
    private SortedMap<Integer, Frame> frameBuffer = new TreeMap<>();
    private int lastFramePlayed = -1;

    // Playback control variables
    private boolean isPlaying = false;
    private boolean userRequestedPlay = false;
    private boolean videoHasEnded = false;
    private ScheduledExecutorService playbackExecutor;
    private ScheduledFuture<?> playbackTask;

    // Buffer thresholds as per requirements
    private final int MAX_BUFFER_SIZE = 100;
    private final int RESUME_THRESHOLD = 80;
    private final int MIN_PLAYBACK_THRESHOLD = 50;
    private final int TARGET_FRAME_RATE = 25; // 25 frames per second
    private final int PLAYBACK_INTERVAL_MS = 40; // 40 ms per frame

    public Session(String server, int port) throws RTSPException {
        rtspConnection = new RTSPConnection(this, server, port);
        playbackExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void addSessionListener(SessionListener listener) {
        sessionListeners.add(listener);
        listener.videoNameChanged(this.videoName);
    }

    public synchronized void removeSessionListener(SessionListener listener) {
        sessionListeners.remove(listener);
    }

    public synchronized void open(String videoName) {
        try {
            // Reset state
            stopPlayback();
            userRequestedPlay = false;
            videoHasEnded = false;
            frameBuffer.clear();
            lastFramePlayed = -1;

            rtspConnection.setup(videoName);
            this.videoName = videoName;

            // Start fetching frames immediately after setup
            rtspConnection.play();

            for (SessionListener listener : sessionListeners)
                listener.videoNameChanged(this.videoName);
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    public synchronized void play() {
        userRequestedPlay = true;

        try {
            // If buffer is empty, we need frames but don't start playback yet
            if (frameBuffer.isEmpty() && !videoHasEnded) {
                rtspConnection.play();
                return;
            }

            // If buffer has enough frames or video has ended with frames available, start playback
            if ((frameBuffer.size() >= MIN_PLAYBACK_THRESHOLD || (videoHasEnded && !frameBuffer.isEmpty())) && !isPlaying) {
                startPlayback();
            }

            // Always ensure server is sending frames if below threshold
            if (frameBuffer.size() < RESUME_THRESHOLD && !videoHasEnded) {
                rtspConnection.play();
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    public synchronized void pause() {
        userRequestedPlay = false;
        stopPlayback();
    }

    public synchronized void close() {
        try {
            stopPlayback();
            userRequestedPlay = false;
            videoHasEnded = false;
            rtspConnection.teardown();
            frameBuffer.clear();
            lastFramePlayed = -1;
            videoName = null;
            for (SessionListener listener : sessionListeners)
                listener.videoNameChanged(this.videoName);
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    private void listenerException(RTSPException e) {
        for (SessionListener listener : sessionListeners)
            listener.exceptionThrown(e);
    }

    public synchronized void closeConnection() {
        stopPlayback();
        rtspConnection.closeConnection();
    }

    public synchronized void processReceivedFrame(Frame frame) {
        if (frame != null) {
            // Only add frame if it's not "too late"
            if (frame.getSequenceNumber() > lastFramePlayed) {
                frameBuffer.put((int) frame.getSequenceNumber(), frame);
            }
        }

        // Handle buffer control based on size
        try {
            if (frameBuffer.size() >= MAX_BUFFER_SIZE) {
                // Pause frame retrieval when buffer is full
                rtspConnection.pause();
            } else if (frameBuffer.size() < RESUME_THRESHOLD && !videoHasEnded) {
                // Resume frame retrieval when buffer needs more frames
                rtspConnection.play();
            }

            // Start playback if user requested play and we now have enough frames
            if (userRequestedPlay && !isPlaying &&
                    (frameBuffer.size() >= MIN_PLAYBACK_THRESHOLD || (videoHasEnded && !frameBuffer.isEmpty()))) {
                startPlayback();
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    public synchronized void videoEnded(int sequenceNumber) {
        videoHasEnded = true;

        // Notify listeners of video end only after all buffered frames are played
        if (frameBuffer.isEmpty()) {
            for (SessionListener listener : sessionListeners) {
                listener.videoEnded();
            }
        } else if (userRequestedPlay && !isPlaying) {
            // If user wants to play and we have frames, start playback
            startPlayback();
        }
    }

    public synchronized String getVideoName() {
        return videoName;
    }

    // Helper method to start playback at the constant rate
    private synchronized void startPlayback() {
        if (isPlaying) return;

        isPlaying = true;
        playbackTask = playbackExecutor.scheduleAtFixedRate(
                this::playNextFrame, 0, PLAYBACK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // Helper method to stop playback
    private synchronized void stopPlayback() {
        isPlaying = false;
        if (playbackTask != null) {
            playbackTask.cancel(false);
            playbackTask = null;
        }
    }

    // Plays the next frame at the constant playback rate (25 fps)
    private synchronized void playNextFrame() {
        if (frameBuffer.isEmpty()) {
            stopPlayback();

            // If video has ended, notify listeners
            if (videoHasEnded) {
                for (SessionListener listener : sessionListeners) {
                    listener.videoEnded();
                }
            }

            // Request more frames if video hasn't ended and buffer is empty
            if (!videoHasEnded) {
                try {
                    rtspConnection.play();
                } catch (RTSPException e) {
                    listenerException(e);
                }
            }
            return;
        }

        // Get the expected next sequence number
        int expectedNextFrame = lastFramePlayed + 1;

        // Handle sequential frame playback and skipping missing frames
        if (frameBuffer.containsKey(expectedNextFrame)) {
            // Play the next sequential frame
            Frame frame = frameBuffer.remove(expectedNextFrame);
            lastFramePlayed = expectedNextFrame;

            // Send frame to listeners (UI)
            for (SessionListener listener : sessionListeners) {
                listener.frameReceived(frame);
            }
        } else if (frameBuffer.firstKey() > expectedNextFrame) {
            // Skip the missing frame but update lastFramePlayed
            lastFramePlayed = expectedNextFrame;
        } else {
            // This shouldn't happen with proper buffer management
            Frame frame = frameBuffer.remove(frameBuffer.firstKey());
            lastFramePlayed = (int) frame.getSequenceNumber();

            for (SessionListener listener : sessionListeners) {
                listener.frameReceived(frame);
            }
        }

        // Request more frames if buffer is getting low
        if (frameBuffer.size() < RESUME_THRESHOLD && !videoHasEnded) {
            try {
                rtspConnection.play();
            } catch (RTSPException e) {
                listenerException(e);
            }
        }
    }
}