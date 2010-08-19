package net.sf.redmine_mylyn.internal.api.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;

import net.sf.redmine_mylyn.api.client.IRedmineApiClient;
import net.sf.redmine_mylyn.api.exception.RedmineApiAuthenticationException;
import net.sf.redmine_mylyn.api.exception.RedmineApiErrorException;
import net.sf.redmine_mylyn.api.exception.RedmineApiHttpStatusException;
import net.sf.redmine_mylyn.internal.api.parser.IModelParser;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HeaderElement;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NTCredentials;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.mylyn.commons.net.AbstractWebLocation;
import org.eclipse.mylyn.commons.net.AuthenticationCredentials;
import org.eclipse.mylyn.commons.net.AuthenticationType;
import org.eclipse.mylyn.commons.net.Policy;
import org.eclipse.mylyn.commons.net.UnsupportedRequestException;
import org.eclipse.mylyn.commons.net.WebUtil;

public abstract class AbstractClient implements IRedmineApiClient {

	protected final static String HEADER_STATUS = "status";

	protected final static String HEADER_REDIRECT = "location";

	protected final static String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate"; //$NON-NLS-1$

	protected final static String HEADER_WWW_AUTHENTICATE_REALM = "realm"; //$NON-NLS-1$
	
	protected final static String REDMINE_REALM = "Redmine API";

	protected final HttpClient httpClient;
	
	protected URL url;
	
	protected String characterEncoding;
	
	protected AbstractWebLocation location;
	
	public AbstractClient(AbstractWebLocation location) {
		this.location = location;
		
		MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
		httpClient = new HttpClient(connectionManager);
		httpClient.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
		httpClient.getParams().setAuthenticationPreemptive(true);
	}

	protected <T extends Object> T executeMethod(HttpMethodBase method, IModelParser<T> parser, IProgressMonitor monitor) throws RedmineApiErrorException {
//		System.out.println("EXECUTE: " + method.getPath());
		return executeMethod(method, parser, monitor, HttpStatus.SC_OK);
	}
	
	protected <T extends Object> T executeMethod(HttpMethodBase method, IModelParser<T> parser, IProgressMonitor monitor, int... expectedSC) throws RedmineApiErrorException {
		monitor = Policy.monitorFor(monitor);
		method.setFollowRedirects(false);
		HostConfiguration hostConfiguration = WebUtil.createHostConfiguration(httpClient, location, monitor);

		T response = null;
		try {
			int sc = performExecuteMethod(method, hostConfiguration, monitor);

			//HTTP-Status 500
			if (sc==HttpStatus.SC_INTERNAL_SERVER_ERROR) {
				Header statusHeader = method.getResponseHeader(HEADER_STATUS);
				String msg = "Server Error";
				if (statusHeader != null) {
					msg += " : " + statusHeader.getValue().replace(""+HttpStatus.SC_INTERNAL_SERVER_ERROR, "").trim();
				}
				
				
				throw new RedmineApiHttpStatusException(sc, msg);
			}

			if (parser!=null && expectedSC != null) {
				Arrays.sort(expectedSC);
				if(Arrays.binarySearch(expectedSC, sc)>=0) {
					InputStream input = WebUtil.getResponseBodyAsStream(method, monitor);
					try {
						response = parser.parseResponse(input, sc);
					} finally {
						input.close();
					}
				} else {
					System.out.println(sc);
					//TODO
//					String msg = Messages.AbstractRedmineClient_UNEXPECTED_RESPONSE_CODE;
//					msg = String.format(msg, sc, method.getPath(), method.getName());
//					IStatus status = new Status(IStatus.ERROR, RedmineCorePlugin.PLUGIN_ID, msg);
//					StatusHandler.fail(status);
//					throw new RedmineStatusException(status);
				}
			}
		} catch (IOException e) {
			throw new RedmineApiErrorException("Execution of method failed", e);
		} finally {
			method.releaseConnection();
		}
		
		return response;
	}
	
	synchronized protected int performExecuteMethod(HttpMethod method, HostConfiguration hostConfiguration, IProgressMonitor monitor) throws RedmineApiErrorException {
		try {
			//complete URL
			String baseUrl = new URL(location.getUrl()).getPath();
			if (!method.getPath().startsWith(baseUrl)) {
				method.setPath(baseUrl + method.getPath());
			}
			
			//Credentials
			AuthenticationCredentials credentials = location.getCredentials(AuthenticationType.REPOSITORY);
			if(credentials!=null && !credentials.getUserName().isEmpty()) {
				String host = hostConfiguration.getHost();
				String username = credentials.getUserName();
				String password = credentials.getPassword();
				
				Credentials httpCredentials = new UsernamePasswordCredentials(username, password);
				int i = username.indexOf("\\");
				if (i > 0 && i < username.length() - 1 && host != null) {
					httpCredentials = new NTCredentials(username.substring(i + 1), password, host, username.substring(0, i));
				}

				AuthScope authScope = new AuthScope(host, hostConfiguration.getPort(), REDMINE_REALM);
				httpClient.getState().setCredentials(authScope, httpCredentials);
			}
			
			
			int sc = WebUtil.execute(httpClient, hostConfiguration, method, monitor);
			
//			if(sc==HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED) {
//				hostConfiguration = refreshCredentials(AuthenticationType.PROXY, method, monitor);
//				return executeMethod(method, hostConfiguration, monitor, authenticated);
//			} else 
			if(sc==HttpStatus.SC_UNAUTHORIZED) {
				hostConfiguration = refreshCredentials(AuthenticationType.HTTP, method, monitor);
				performExecuteMethod(method, hostConfiguration, monitor);
			}
			
			return sc;
		} catch (RuntimeException e) {
			throw new RedmineApiErrorException("Execution of method failed - unexpected RuntimeException", e);
		} catch (IOException e) {
			throw new RedmineApiErrorException("Execution of method failed", e);
		}
	}
	
	protected HostConfiguration refreshCredentials(AuthenticationType authenticationType, HttpMethod method, IProgressMonitor monitor) throws RedmineApiErrorException {
		if (Policy.isBackgroundMonitor(monitor)) {
			throw new RedmineApiAuthenticationException("Credentials not stored, manually syncronization required");
		}
		
		try {
			String message = "Authentication required";
			if(authenticationType.equals(AuthenticationType.HTTP)) {
				Header authHeader = method.getResponseHeader(HEADER_WWW_AUTHENTICATE);
				if(authHeader!=null) {
					for (HeaderElement headerElem : authHeader.getElements()) {
						if (headerElem.getName().contains(HEADER_WWW_AUTHENTICATE_REALM)) {
							
							if(headerElem.getValue().equals(REDMINE_REALM)) {
								authenticationType = AuthenticationType.REPOSITORY;
							} else {
								throw new RedmineApiErrorException("Additional Http-Auth is currently not supported.");
							}
							break;
						}
					}
				}
			}
			location.requestCredentials(authenticationType, message, monitor);
			
			return WebUtil.createHostConfiguration(httpClient, location, monitor);
		} catch (UnsupportedRequestException e) {
			throw new RedmineApiAuthenticationException("Request credentials failed - not supported", e);
		} catch (OperationCanceledException e) {
			monitor.setCanceled(true);
			throw new RedmineApiAuthenticationException("Authentication canceled");
		}
	}

}