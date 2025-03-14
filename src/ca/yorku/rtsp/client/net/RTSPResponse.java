/*
 * Author: Jonatan Schroeder
 * Updated: March 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * This class represents an RTSP response. The method
 * `readRTSPResponse` is used to read a response from a
 * BufferedReader (usually associated to a socket).
 */
public class RTSPResponse {

    private String rtspVersion;
    private int responseCode;
    private String responseMessage;
    private Map<String, String> headers;

    /**
     * Creates an RTSP response.
     *
     * @param rtspVersion     The String representation of the RTSP version (e.g., "RTSP/1.0").
     * @param responseCode    The response code corresponding the result of the requested operation.
     * @param responseMessage The response message associated to the response code.
     */
    public RTSPResponse(String rtspVersion, int responseCode, String responseMessage) {
        this.rtspVersion = rtspVersion;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.headers = new HashMap<>();
    }

    /**
     * Reads an RTSP response from a BufferedReader.
     *
     * @param reader The BufferedReader from which to read the RTSP response.
     * @return An RTSPResponse object containing the parsed response.
     * @throws IOException If an I/O error occurs while reading from the BufferedReader.
     * @throws IllegalArgumentException If the response format is invalid or cannot be properly parsed.
     */
    public static RTSPResponse readRTSPResponse(BufferedReader reader) throws IOException, IllegalArgumentException {
        // Read the status line
        String statusLine = reader.readLine();
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IllegalArgumentException("Invalid RTSP response: empty status line");
        }

        // Parse the status line
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 3) {
            throw new IllegalArgumentException("Invalid RTSP response format: " + statusLine);
        }

        String rtspVersion = statusParts[0];
        int responseCode;
        try {
            responseCode = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid response code: " + statusParts[1]);
        }
        String responseMessage = statusParts[2];

        // Create the response object
        RTSPResponse response = new RTSPResponse(rtspVersion, responseCode, responseMessage);

        // Read headers
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            // Split the header line into name and value
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String headerName = headerLine.substring(0, colonIndex).trim();
                String headerValue = headerLine.substring(colonIndex + 1).trim();
                response.addHeaderValue(headerName, headerValue);
            }
        }

        return response;
    }

    /**
     * Returns the RTSP version included in the response. It is expected to be "RTSP/1.0".
     *
     * @return A String representing the RTSP version read from the response.
     */
    public String getRtspVersion() {
        return rtspVersion;
    }

    /**
     * Returns the numeric response code included in the response. The code 200 represent a successful response, while a
     * code between 400 and 599 represents an error.
     *
     * @return The response code of the RTSP response.
     */
    public int getResponseCode() {
        return responseCode;
    }

    /**
     * Returns the response message associated to the response code. It should not be used for any automated
     * verification, and is usually only intended for human users.
     *
     * @return A String representing the message associated to the response code.
     */
    public String getResponseMessage() {
        return responseMessage;
    }

    /**
     * Returns the value of the named header field.
     *
     * @param headerName The name of the header field to be retrieved. Header names are case-insensitive.
     * @return The value of the header field named by headerName, or null if that header wasn't included in the
     * response.
     */
    public String getHeaderValue(String headerName) {
        return headers.get(headerName.toUpperCase());
    }

    /**
     * Sets the value of a named header field.
     *
     * @param headerName  The name of the header field to be updated. Header names are case-insensitive.
     * @param headerValue The new value of the header field.
     */
    public void addHeaderValue(String headerName, String headerValue) {
        headers.put(headerName.toUpperCase(), headerValue);
    }

    @Override
    public String toString() {
        return responseCode + " '" + responseMessage + "'";
    }
}