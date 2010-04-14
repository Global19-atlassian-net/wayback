/* ARCUnwrappingProxy
 *
 * $Id$:
 *
 * Created on Dec 10, 2009.
 *
 * Copyright (C) 2006 Internet Archive.
 *
 * This file is part of Wayback.
 *
 * Wayback is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * any later version.
 *
 * Wayback is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with Wayback; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.archive.wayback.liveweb;

import java.io.IOException;
import java.util.zip.GZIPInputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.archive.io.arc.ARCRecord;
import org.archive.wayback.util.ByteOp;
import org.archive.wayback.webapp.ServletRequestContext;

/**
 * 
 * ServletRequestContext which proxies to an ARCRecordingProxy, and unwraps 
 * the "application/x-arc-record" MIME response into the inner HTTP response,
 * sending all HTTP headers AS-IS, and the HTTP Entity.
 * 
 * Can be used to use an ARCRecordingProxy with a UserAgent expecting real
 * HTTP responses, not "application/x-arc-record". A web browser for example.
 * 
 * @author brad
 *
 */
public class ARCUnwrappingProxy extends ServletRequestContext {
	
    private MultiThreadedHttpConnectionManager connectionManager = null;
    private HostConfiguration hostConfiguration = null;
    /**
     * 
     */
    public ARCUnwrappingProxy() {
    	connectionManager = new MultiThreadedHttpConnectionManager();
    	hostConfiguration = new HostConfiguration();
    }

//	protected HttpClient http = new HttpClient(
//            new MultiThreadedHttpConnectionManager());

	/* (non-Javadoc)
	 * @see org.archive.wayback.webapp.ServletRequestContext#handleRequest(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	public boolean handleRequest(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {
		StringBuffer sb = httpRequest.getRequestURL();
		String query = httpRequest.getQueryString();
		if(query != null) {
			sb.append("?").append(query);
		}
//		URL url = new URL(sb.toString());
        HttpMethod method = new GetMethod(sb.toString());
//        method.addRequestHeader("User-Agent", userAgent);
        boolean got200 = false;
        try {
        	HttpClient http = new HttpClient(connectionManager);
        	http.setHostConfiguration(hostConfiguration);

        	int status = http.executeMethod(method);
        	if(status == 200) {
        		ARCRecord r = 
        			new ARCRecord(new GZIPInputStream(
        					method.getResponseBodyAsStream()),
        					"id",0L,false,false,true);
        		r.skipHttpHeader();
        		httpResponse.setStatus(r.getStatusCode());
        		Header headers[] = r.getHttpHeaders();
        		for(Header header : headers) {
        			httpResponse.addHeader(header.getName(), header.getValue());
        		}

        		ByteOp.copyStream(r, httpResponse.getOutputStream());
        		got200 = true;
        	}
        } finally {
        	method.releaseConnection();

        }
        
		return got200;
	}

    /**
     * @param hostPort location of ARCRecordingProxy ServletRequestContext, ex:
     *   "localhost:3128"
     */
    public void setProxyHostPort(String hostPort) {
    	int colonIdx = hostPort.indexOf(':');
    	if(colonIdx > 0) {
    		String host = hostPort.substring(0,colonIdx);
    		int port = Integer.valueOf(hostPort.substring(colonIdx+1));
    		hostConfiguration.setProxy(host, port);
    	}
    }
}
