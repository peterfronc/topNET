/*
 * topNET
 * Fast HTTP AbstractServer Solution.
 * Copyright 2016, Qubit Group <www.qubit.com>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  
 * If not, see <https://www.gnu.org/licenses/lgpl-3.0.en.html>
 * 
 * Author: Peter Fronc <peter.fronc@qubitdigital.com>
 */

package com.qubit.topnet;

import static com.qubit.topnet.DataHandler.getDefaultProtocol;
import com.qubit.topnet.exceptions.ResponseBuildingStartedException;
import com.qubit.topnet.exceptions.TooLateToChangeHeadersException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Peter Fronc <peter.fronc@qubitdigital.com>
 */
public class Response {

  public static String serverName;
  
  static private final String CRLF = "\r\n";
  static private final String OK_200 = "200 OK" + CRLF;
  static private final String OK_204 = "204 No Content" + CRLF;

  private static final ThreadLocal<ServerTime> serverTime;
  
  static final Logger log = Logger.getLogger(Response.class.getName());

  static {
    serverName = "topNET";// + "/" + SERVER_VERSION;
    serverTime = new ThreadLocal<ServerTime>() {
      @Override
      protected ServerTime initialValue() {
        return new ServerTime();
      }
    };
  }

  private static void appendServerHeaer(StringBuilder buffer) {
    if (Response.serverName != null) {
      buffer.append("Server: ");
      buffer.append(Response.serverName);
      buffer.append(CRLF);
    }
  }
  
  /**
   * @return the serverName
   */
  public static String getServerName() {
    return serverName;
  }

  /**
   * @param aServerName the serverName to set
   */
  public static void setServerName(String aServerName) {
    serverName = aServerName;
  }
  
  private int httpCode = 200;
  List<String[]> headers = new ArrayList<>();
  private ResponseStream responseStream;
  private boolean tooLateToChangeHeaders;
  private long contentLength = -1;
  private String contentType = "text/html";
  private String charset;
  private boolean forcingClosingAfterRequest = false;
  private boolean tellingConnectionClose = false;
  private volatile boolean moreDataComing = false;

  private StringBuilder stringBuffer = null;
  private InputStream inputStreamForBody;
  private Object attachment;
  private char[] httpProtocol;

  public void init (char[] httpProtocol) {
    this.httpProtocol = httpProtocol;
  }
  
  public Response () {
    this.httpProtocol = getDefaultProtocol();
  }
  
  /**
   * Called just before starting reading to output.
   *
   * @return ResponseStream reader object for this response
   */
  public final ResponseStream getResponseReaderReadyToRead() {
    this.prepareResponseReader();
    return this.getResponseStream();
  }

  public void setResponseStream(ResponseStream responseStream)
          throws ResponseBuildingStartedException {
    if (this.responseStream != null) {
      throw new ResponseBuildingStartedException();
    }
    this.responseStream = responseStream;
  }

  public ByteArrayInputStream getHeadersToSend() {

    StringBuilder buffer = getHeadersBuffer();

    ByteArrayInputStream stream
            = new ByteArrayInputStream(
                    buffer.toString().getBytes(StandardCharsets.ISO_8859_1));

    return stream;
  }

  public StringBuilder getHeadersBuffer() {
    StringBuilder buffer
            = getHeadersBufferWithoutEOL(
                    this.httpCode,
                    this.getHttpProtocol());

    try {
      this.addHeaders(buffer);
    } catch (TooLateToChangeHeadersException ex) {
      log.log(Level.SEVERE, "This should never happen.", ex);
    }

    buffer.append(CRLF); // headers are done\

    return buffer;
  }

  public static StringBuilder getHeadersBufferWithoutEOL(
          int httpCode, char[] httpProtocol) {

    StringBuilder buffer = new StringBuilder();
    int httpCodeNum = httpCode;

    buffer.append(httpProtocol);
    buffer.append(' ');

    switch (httpCodeNum) {
      case 200:
        buffer.append(OK_200);
        break;
      case 204:
        buffer.append(OK_204);
        break;
      case 404:
        buffer.append("404 Not Found");
        buffer.append(CRLF);
        break;
      case 400:
        buffer.append("400 Bad Request");
        buffer.append(CRLF);
        break;
      case 503:
        buffer.append("503 Server Error");
        buffer.append(CRLF);
        break;
      default:
        buffer.append(httpCodeNum);
        buffer.append(CRLF);
        break;
    }

    buffer.append("Date: ");
    buffer.append(serverTime.get().getCachedTime());
    buffer.append(CRLF);

    Response.appendServerHeaer(buffer);

    return buffer;
  }

  private void addHeaders(StringBuilder buffer)
          throws TooLateToChangeHeadersException {
    for (String[] header : headers) {
      buffer.append(header[0]);
      buffer.append(": ");
      buffer.append(header[1]);
      buffer.append(CRLF);
    }
  }

  public void addHeader(String name, String value)
          throws TooLateToChangeHeadersException {
    if (this.isTooLateToChangeHeaders()) {
      throw new TooLateToChangeHeadersException();
    }
    this.headers.add(new String[]{name, value});
  }

  public void removeHeader(String name)
          throws TooLateToChangeHeadersException {
    if (this.isTooLateToChangeHeaders()) {
      throw new TooLateToChangeHeadersException();
    }
    for (int i = 0; i < headers.size();) {
      if (headers.get(i)[0].equals(name)) {
        headers.remove(i);
      } else {
        i++;
      }
    }
  }

  public List<String> getHeaders(String name) {
    List<String> ret = new ArrayList<>();
    for (String[] header : this.headers) {
      if (header[0].equals(name)) {
        ret.add(header[1]);
      }
    }
    return ret;
  }
  
  public String getHeader(String name) {
    for (String[] header : headers) {
      if (header[0].equals(name)) {
        return header[1];
      }
    }
    return null;
  }

  /**
   * @param httpCode the httpCodeNum to set
   * @throws com.qubit.topnet.exceptions.TooLateToChangeHeadersException
   */
  public void setHttpCode(int httpCode) throws TooLateToChangeHeadersException {
    if (this.isTooLateToChangeHeaders()) {
      throw new TooLateToChangeHeadersException();
    }
    this.httpCode = httpCode;
  }

  public int getHttpCode()  {
    return this.httpCode;
  }
  
  /**
   * To print to response output - use this function - note this is textual
   * method of sending response. To send binary data, preapare input stream to
   * read and attach here.
   *
   * @param str
   * @throws ResponseBuildingStartedException
   */
  public void print(String str) throws ResponseBuildingStartedException {
    if (this.getResponseStream() != null) {
      throw new ResponseBuildingStartedException();
    }
    if (this.getStringBuffer() == null) {
      this.setStringBuffer(new StringBuilder());
    }
    this.getStringBuffer().append(str);
  }

  protected void prepareResponseReader() {
    if (this.responseStream == null) {
      if (this.getStringBuffer() != null) {
        String charsetString = this.getCharset();

        Charset _charset = Charset.defaultCharset();

        if (charsetString != null) {
          _charset = Charset.forName(getCharset());
        }

        charsetString = _charset.name();

        byte[] bytes = this.getStringBuffer().toString().getBytes(_charset);
        // @todo its copying... lets do that without it
        if (this.getContentLength() < 0) { // only if not 
          this.setContentLength(bytes.length);
        }
        
        ByteArrayInputStream bodyStream = new ByteArrayInputStream(bytes);

        try {

          if (this.isTellingConnectionClose() || 
              ServerBase.isTellingConnectionClose()) {
            this.addHeader("Connection", "close");
          }

          if (this.getHeader("Content-Type") == null) {
            StringBuilder sb = new StringBuilder(this.getContentType());
            sb.append("; charset=");
            sb.append(charsetString);
            this.addHeader("Content-Type", sb.toString());
          }
        } catch (TooLateToChangeHeadersException ex) {
          log.log(Level.SEVERE,
                 "This should never happen - bad implementation.", ex);
        }

        this.getStringBuffer().setLength(0);
        // @todo setter may be a good idea here
        this.responseStream = 
            prepareResponseStreamFromInputStream(bodyStream);
        
      } else {
        InputStream readingFrom = this.getStreamToReadFrom();
        if (readingFrom == null) {
          this.setContentLength(0);
        }
        this.responseStream = 
            prepareResponseStreamFromInputStream(readingFrom);
      }
    }
    
    if (this.getResponseStream().getHeadersStream() == null) { // only once
      this.prepareContentLengthHeader();
      this.getResponseStream().setHeadersStream(getHeadersToSend());
    }
  }

  /**
   * @return the contentLength
   */
  public long getContentLength() {
    return contentLength;
  }

  /**
   * @param contentLength the contentLength to set
   */
  public void setContentLength(long contentLength) {
    this.contentLength = contentLength;
  }

  public void prepareContentLengthHeader() {
    try {
      if (this.contentLength >= 0) {
        this.addHeader("Content-Length", Long.toString(this.contentLength));
      } else {
        this.removeHeader("Content-Length");
      }
    } catch (TooLateToChangeHeadersException ex) {
      log.warning("Content length header set too late.");
    }
    
  }

  /**
   * @return the contentType
   */
  public String getContentType() {
    return contentType;
  }

  /**
   * @param contentType the contentType to set
   */
  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  /**
   * @return the charset
   */
  public String getCharset() {
    return charset;
  }

  /**
   * @param charset the charset to set
   */
  public void setCharset(String charset) {
    this.charset = charset;
  }

  /**
   * @return the forcingClosingAfterRequest
   */
  public boolean isForcingClosingAfterRequest() {
    return forcingClosingAfterRequest;
  }

  /**
   * @param forcingCloseConnection the forcingClosingAfterRequest to set
   */
  public void setForcingClosingAfterRequest(boolean forcingCloseConnection) {
    this.forcingClosingAfterRequest = forcingCloseConnection;
  }

  /**
   * @return the tellingConnectionClose
   */
  public boolean isTellingConnectionClose() {
    return tellingConnectionClose;
  }

  /**
   * @param inputStream the inputStreamForBody to set
   * @throws com.qubit.topnet.exceptions.ResponseBuildingStartedException
   */
  public void setStreamToReadFrom(InputStream inputStream)
          throws ResponseBuildingStartedException {
    if (this.getStringBuffer() != null) {
      throw new ResponseBuildingStartedException();
    }

    this.inputStreamForBody = inputStream;
    if (this.getResponseStream() != null) {
      this.getResponseStream().setBodyStream(this.getStreamToReadFrom());
    }
  }

  public boolean waitForData() {
    return false;
  }

  /**
   * @return the moreDataComing
   */
  public boolean isMoreDataComing() {
    return moreDataComing;
  }

  /**
   * @param moreDataComing the moreDataComing to set
   */
  public void setMoreDataComing(boolean moreDataComing) {
    this.moreDataComing = moreDataComing;
  }

  /**
   * @return the attachment
   */
  public Object getAttachment() {
    return attachment;
  }

  /**
   * @param attachment the attachment to set
   */
  public void setAttachment(Object attachment) {
    this.attachment = attachment;
  }

  /**
   * @param tellingConnectionClose the tellingConnectionClose to set
   */
  public void setTellingConnectionClose(boolean tellingConnectionClose) {
    this.tellingConnectionClose = tellingConnectionClose;
  }

  /**
   * @return the responseStream
   */
  public ResponseStream getResponseStream() {
    return responseStream;
  }

  /**
   * @return the httpProtocol
   */
  public char[] getHttpProtocol() {
    return httpProtocol;
  }

  /**
   * @param httpProtocol the httpProtocol to set
   */
  public void setHttpProtocol(char[] httpProtocol) {
    this.httpProtocol = httpProtocol;
  }

  /**
   * @return the tooLateToChangeHeaders
   */
  public boolean isTooLateToChangeHeaders() {
    return tooLateToChangeHeaders;
  }

  /**
   * @param tooLateToChangeHeaders the tooLateToChangeHeaders to set
   */
  public void setTooLateToChangeHeaders(boolean tooLateToChangeHeaders) {
    this.tooLateToChangeHeaders = tooLateToChangeHeaders;
  }

  /**
   * @return the stringBuffer
   */
  public StringBuilder getStringBuffer() {
    return stringBuffer;
  }

  /**
   * @param stringBuffer the stringBuffer to set
   */
  public void setStringBuffer(StringBuilder stringBuffer) {
    this.stringBuffer = stringBuffer;
  }

  public static ResponseStream prepareResponseStreamFromInputStream(
          InputStream bodyStream) {
    ResponseStream r = new ResponseStream();
    r.setBodyStream(bodyStream);
    return r;
  }

  /**
   * @return the inputStreamForBody
   */
  public InputStream getStreamToReadFrom() {
    return inputStreamForBody;
  }

  public void reset() {
    if (headers != null) {
      headers.clear();
    }
    
    httpCode = 200;
    responseStream = null;
    tooLateToChangeHeaders = false;
    contentLength = -1;
    contentType = "text/html";
    charset = null;
    forcingClosingAfterRequest = false;
    tellingConnectionClose = false;
    moreDataComing = false;
    stringBuffer = null;
    inputStreamForBody = null;
    attachment = null;
    httpProtocol = null;
  }
  
  /**
   * @return the headers
   */
  public List<String[]> getHeaders() {
    return headers;
  }
}