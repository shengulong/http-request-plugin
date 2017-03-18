package jenkins.plugins.http_request;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.google.common.base.Strings;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Items;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.VariableResolver;

import jenkins.plugins.http_request.auth.Authenticator;
import jenkins.plugins.http_request.auth.BasicDigestAuthentication;
import jenkins.plugins.http_request.auth.FormAuthentication;
import jenkins.plugins.http_request.util.HttpClientUtil;
import jenkins.plugins.http_request.util.HttpRequestNameValuePair;
import jenkins.plugins.http_request.util.RequestAction;

/**
 * @author Janario Oliveira
 */
public class HttpRequest extends Builder {

    private @Nonnull String url;
	private Boolean ignoreSslErrors = DescriptorImpl.ignoreSslErrors;
	private HttpMode httpMode                 = DescriptorImpl.httpMode;
    private Boolean passBuildParameters       = DescriptorImpl.passBuildParameters;
    private String validResponseCodes         = DescriptorImpl.validResponseCodes;
    private String validResponseContent       = DescriptorImpl.validResponseContent;
    private MimeType acceptType               = DescriptorImpl.acceptType;
    private MimeType contentType              = DescriptorImpl.contentType;
    private String outputFile                 = DescriptorImpl.outputFile;
    private Integer timeout                   = DescriptorImpl.timeout;
    private Boolean consoleLogResponseBody    = DescriptorImpl.consoleLogResponseBody;
    private String authentication             = DescriptorImpl.authentication;
    private String requestBody                = DescriptorImpl.requestBody;
    private List<HttpRequestNameValuePair> customHeaders = DescriptorImpl.customHeaders;

	@DataBoundConstructor
	public HttpRequest(@Nonnull String url) {
		this.url = url;
	}

	@Nonnull
	public String getUrl() {
		return url;
	}

	public Boolean getIgnoreSslErrors() {
		return ignoreSslErrors;
	}

	@DataBoundSetter
	public void setIgnoreSslErrors(Boolean ignoreSslErrors) {
		this.ignoreSslErrors = ignoreSslErrors;
	}

	public HttpMode getHttpMode() {
		return httpMode;
	}

	@DataBoundSetter
	public void setHttpMode(HttpMode httpMode) {
		this.httpMode = httpMode;
	}

	public Boolean getPassBuildParameters() {
		return passBuildParameters;
	}

	@DataBoundSetter
	public void setPassBuildParameters(Boolean passBuildParameters) {
		this.passBuildParameters = passBuildParameters;
	}

	@Nonnull
	public String getValidResponseCodes() {
		return validResponseCodes;
	}

	@DataBoundSetter
	public void setValidResponseCodes(String validResponseCodes) {
		this.validResponseCodes = validResponseCodes;
	}

	public String getValidResponseContent() {
		return validResponseContent;
	}

	@DataBoundSetter
	public void setValidResponseContent(String validResponseContent) {
		this.validResponseContent = validResponseContent;
	}

	public MimeType getAcceptType() {
		return acceptType;
	}

	@DataBoundSetter
	public void setAcceptType(MimeType acceptType) {
		this.acceptType = acceptType;
	}

	public MimeType getContentType() {
		return contentType;
	}

	@DataBoundSetter
	public void setContentType(MimeType contentType) {
		this.contentType = contentType;
	}

	public String getOutputFile() {
		return outputFile;
	}

	@DataBoundSetter
	public void setOutputFile(String outputFile) {
		this.outputFile = outputFile;
	}

	public Integer getTimeout() {
		return timeout;
	}

	@DataBoundSetter
	public void setTimeout(Integer timeout) {
		this.timeout = timeout;
	}

	public Boolean getConsoleLogResponseBody() {
		return consoleLogResponseBody;
	}

	@DataBoundSetter
	public void setConsoleLogResponseBody(Boolean consoleLogResponseBody) {
		this.consoleLogResponseBody = consoleLogResponseBody;
	}

	public String getAuthentication() {
		return authentication;
	}

	@DataBoundSetter
	public void setAuthentication(String authentication) {
		this.authentication = authentication;
	}

	public String getRequestBody() {
		return requestBody;
	}

	@DataBoundSetter
	public void setRequestBody(String requestBody) {
		this.requestBody = requestBody;
	}

	public List<HttpRequestNameValuePair> getCustomHeaders() {
		return customHeaders;
	}

	@DataBoundSetter
	public void setCustomHeaders(List<HttpRequestNameValuePair> customHeaders) {
		this.customHeaders = customHeaders;
	}

	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	public static void xStreamCompatibility() {
		Items.XSTREAM2.aliasField("logResponseBody", HttpRequest.class, "consoleLogResponseBody");
		Items.XSTREAM2.aliasField("consoleLogResponseBody", HttpRequest.class, "consoleLogResponseBody");
		Items.XSTREAM2.alias("pair", HttpRequestNameValuePair.class);
	}

	protected Object readResolve() {
		if (customHeaders == null) {
			customHeaders = DescriptorImpl.customHeaders;
		}
		if (validResponseCodes == null || validResponseCodes.trim().isEmpty()) {
			validResponseCodes = DescriptorImpl.validResponseCodes;
		}
		if (ignoreSslErrors == null) {
			//default for new job false(DescriptorImpl.ignoreSslErrors) for old ones true to keep same behavior
			ignoreSslErrors = true;
		}
		return this;
	}

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener)
    throws InterruptedException, IOException
    {
        final PrintStream logger = listener.getLogger();
        final EnvVars envVars = build.getEnvironment(listener);
        final VariableResolver<String> buildVariableResolver = build.getBuildVariableResolver();

        String evaluatedUrl = evaluate(url, buildVariableResolver, envVars);
        String evaluatedBody = evaluate(requestBody, buildVariableResolver, envVars);

        final List<HttpRequestNameValuePair> params = createParameters(build, logger, envVars);
        final List<HttpRequestNameValuePair> headers = new ArrayList<>();

        if (contentType != MimeType.NOT_SET) {
            headers.add(new HttpRequestNameValuePair("Content-type", contentType.getContentType().toString()));
        }
        if (acceptType != MimeType.NOT_SET) {
            headers.add(new HttpRequestNameValuePair("Accept", acceptType.getValue()));
        }
        for (HttpRequestNameValuePair header : customHeaders) {
            String headerName = evaluate(header.getName(), buildVariableResolver, envVars);
            String headerValue = evaluate(header.getValue(), buildVariableResolver, envVars);
            boolean maskValue = header.getMaskValue();

            headers.add(new HttpRequestNameValuePair(headerName, headerValue, maskValue));
        }

        RequestAction requestAction = new RequestAction(new URL(evaluatedUrl), httpMode, evaluatedBody, params, headers, contentType.getContentType());

        ResponseContentSupplier responseContentSupplier = performHttpRequest(listener, requestAction);

        logResponseToFile(build.getWorkspace(), logger, responseContentSupplier);
        return true;
    }

    public ResponseContentSupplier performHttpRequest(TaskListener listener)
    throws InterruptedException, IOException
    {
        List<HttpRequestNameValuePair> params = Collections.emptyList();
        List<HttpRequestNameValuePair> headers = new ArrayList<>();
        if (contentType != MimeType.NOT_SET) {
            headers.add(new HttpRequestNameValuePair("Content-type", contentType.getContentType().toString()));
        }
        if (acceptType != MimeType.NOT_SET) {
            headers.add(new HttpRequestNameValuePair("Accept", acceptType.getValue()));
        }
        for (HttpRequestNameValuePair header : customHeaders) {
            headers.add(new HttpRequestNameValuePair(header.getName(), header.getValue(), header.getMaskValue()));
        }

        RequestAction requestAction = new RequestAction(new URL(url), httpMode, requestBody, params, headers, contentType.getContentType());

        return performHttpRequest(listener, requestAction);
    }

    public ResponseContentSupplier performHttpRequest(TaskListener listener, RequestAction requestAction)
    throws InterruptedException, IOException
    {
        final PrintStream logger = listener.getLogger();
        logger.println("HttpMode: " + requestAction.getMode());
        logger.println(String.format("URL: %s", requestAction.getUrl()));
        for (HttpRequestNameValuePair header : requestAction.getHeaders()) {
            if (header.getMaskValue() || header.getName().equalsIgnoreCase("Authorization")) {
              logger.println(header.getName() + ": *****");
            } else {
              logger.println(header.getName() + ": " + header.getValue());
            }
        }

		ResponseContentSupplier responseContentSupplier = authAndRequest(requestAction, logger);
		responseCodeIsValid(responseContentSupplier, logger);
		contentIsValid(responseContentSupplier, logger);

		return responseContentSupplier;
	}

	private ResponseContentSupplier authAndRequest(RequestAction requestAction, PrintStream logger) throws IOException, InterruptedException {
		CloseableHttpClient httpclient = null;
		try {
			HttpClientBuilder clientBuilder = HttpClientBuilder.create().useSystemProperties();
			//timeout
			if (timeout != null) {
				int t = timeout * 1000;
				RequestConfig config = RequestConfig.custom()
						.setSocketTimeout(t)
						.setConnectTimeout(t)
						.setConnectionRequestTimeout(t)
						.build();
				clientBuilder.setDefaultRequestConfig(config);
			}
			//ssl
			if (ignoreSslErrors) {
				try {
					SSLContextBuilder builder = SSLContextBuilder.create();
					builder.loadTrustMaterial(null, new TrustStrategy() {
						@Override
						public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
							return true;
						}
					});
					SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builder.build(), NoopHostnameVerifier.INSTANCE);
					clientBuilder.setSSLSocketFactory(sslsf);
				} catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException e) {
					throw new IllegalStateException(e);
				}
			}

			HttpClientUtil clientUtil = new HttpClientUtil();
			HttpRequestBase httpRequestBase = clientUtil.createRequestBase(requestAction);
			HttpContext context = new BasicHttpContext();

			httpclient = auth(logger, clientBuilder, httpRequestBase, context);
			return executeRequest(logger, httpclient, clientUtil, httpRequestBase, context);
		} finally {
			if (httpclient != null) {
				httpclient.close();
			}
		}
	}

	private CloseableHttpClient auth(PrintStream logger, HttpClientBuilder clientBuilder, HttpRequestBase httpRequestBase, HttpContext context) throws IOException, InterruptedException {
		if (authentication != null && !authentication.isEmpty()) {
			final Authenticator auth = HttpRequestGlobalConfig.get().getAuthentication(authentication);
			if (auth == null) {
				throw new IllegalStateException("Authentication '" + authentication + "' doesn't exist anymore");
			}

			logger.println("Using authentication: " + auth.getKeyName());
			return auth.authenticate(clientBuilder, context, httpRequestBase, logger);
		}
		return clientBuilder.build();
	}

	private ResponseContentSupplier executeRequest(PrintStream logger, CloseableHttpClient httpclient, HttpClientUtil clientUtil, HttpRequestBase httpRequestBase, HttpContext context) throws IOException, InterruptedException {
		ResponseContentSupplier responseContentSupplier;
		try {
			final HttpResponse response = clientUtil.execute(httpclient, context, httpRequestBase, logger);
			// The HttpEntity is consumed by the ResponseContentSupplier
			responseContentSupplier = new ResponseContentSupplier(response);
		} catch (UnknownHostException uhe) {
			logger.println("Treating UnknownHostException(" + uhe.getMessage() + ") as 404 Not Found");
			responseContentSupplier = new ResponseContentSupplier("UnknownHostException as 404 Not Found", 404);
		} catch (SocketTimeoutException | ConnectException ce) {
			logger.println("Treating " + ce.getClass() + "(" + ce.getMessage() + ") as 408 Request Timeout");
			responseContentSupplier = new ResponseContentSupplier(ce.getClass() + "(" + ce.getMessage() + ") as 408 Request Timeout", 408);
		}

		if (consoleLogResponseBody) {
			logger.println("Response: \n" + responseContentSupplier.getContent());
		}
		return responseContentSupplier;
	}

	private void contentIsValid(ResponseContentSupplier responseContentSupplier, PrintStream logger)
    throws AbortException
    {
        if (Strings.isNullOrEmpty(validResponseContent)) {
            return;
        }

        String response = responseContentSupplier.getContent();
        if (!response.contains(validResponseContent)) {
            throw new AbortException("Fail: Response with length " + response.length() + " doesn't contain '" + validResponseContent + "'");
        }
        return;
    }

    private void responseCodeIsValid(ResponseContentSupplier response, PrintStream logger)
    throws AbortException
    {
        List<Range<Integer>> ranges = DescriptorImpl.parseToRange(validResponseCodes);
        for (Range<Integer> range : ranges) {
            if (range.contains(response.getStatus())) {
                logger.println("Success code from " + range);
                return;
            }
        }
        throw new AbortException("Fail: the returned code " + response.getStatus()+" is not in the accepted range: "+ranges);
    }

    private void logResponseToFile(FilePath workspace, PrintStream logger, ResponseContentSupplier responseContentSupplier) throws IOException, InterruptedException {

        FilePath outputFilePath = getOutputFilePath(workspace, logger);

        if (outputFilePath != null && responseContentSupplier.getContent() != null) {
            OutputStreamWriter write = null;
            try {
                write = new OutputStreamWriter(outputFilePath.write(), Charset.forName("UTF-8"));
                write.write(responseContentSupplier.getContent());
            } finally {
                if (write != null) {
                    write.close();
                }
            }
        }
    }

    private FilePath getOutputFilePath(FilePath workspace, PrintStream logger) {
        if (outputFile != null && !outputFile.isEmpty()) {
            return workspace.child(outputFile);
        }
        return null;
    }

    private List<HttpRequestNameValuePair> createParameters(
            AbstractBuild<?, ?> build, PrintStream logger,
            EnvVars envVars) {
        if (passBuildParameters == null || !passBuildParameters) {
            return Collections.emptyList();
        }

        if (!envVars.isEmpty()) {
            logger.println("Parameters: ");
        }

        final VariableResolver<String> vars = build.getBuildVariableResolver();

        List<HttpRequestNameValuePair> l = new ArrayList<HttpRequestNameValuePair>();
        for (Map.Entry<String, String> entry : build.getBuildVariables().entrySet()) {
            String value = evaluate(entry.getValue(), vars, envVars);
            logger.println("  " + entry.getKey() + " = " + value);

            l.add(new HttpRequestNameValuePair(entry.getKey(), value));
        }

        return l;
    }

    private String evaluate(String value, VariableResolver<String> vars, Map<String, String> env) {
        return Util.replaceMacro(Util.replaceMacro(value, vars), env);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		public static final boolean ignoreSslErrors = false;
		public static final HttpMode httpMode                  = HttpMode.GET;
        public static final Boolean  passBuildParameters       = false;
        public static final String   validResponseCodes        = "100:399";
        public static final String   validResponseContent      = "";
        public static final MimeType acceptType                = MimeType.NOT_SET;
        public static final MimeType contentType               = MimeType.NOT_SET;
        public static final String   outputFile                = "";
        public static final int      timeout                   = 0;
        public static final Boolean  consoleLogResponseBody    = false;
        public static final String   authentication            = "";
        public static final String   requestBody               = "";
        public static final List <HttpRequestNameValuePair> customHeaders = Collections.<HttpRequestNameValuePair>emptyList();

        public DescriptorImpl() {
            load();
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "HTTP Request";
        }

        public ListBoxModel doFillHttpModeItems() {
            return HttpMode.getFillItems();
        }

        public ListBoxModel doFillAcceptTypeItems() {
            return MimeType.getContentTypeFillItems();
        }

        public ListBoxModel doFillContentTypeItems() {
            return MimeType.getContentTypeFillItems();
        }

        public ListBoxModel doFillAuthenticationItems() {
            return fillAuthenticationItems();
        }

        public static ListBoxModel fillAuthenticationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("");
            for (BasicDigestAuthentication basicDigestAuthentication : HttpRequestGlobalConfig.get().getBasicDigestAuthentications()) {
                items.add(basicDigestAuthentication.getKeyName());
            }
            for (FormAuthentication formAuthentication : HttpRequestGlobalConfig.get().getFormAuthentications()) {
                items.add(formAuthentication.getKeyName());
            }

            return items;
        }

        public FormValidation doCheckUrl(@QueryParameter String value)
                throws IOException, ServletException {
            return FormValidation.ok();
        }

        public FormValidation doValidateKeyName(@QueryParameter String value) {
            return validateKeyName(value);
        }

        public static FormValidation validateKeyName(String value) {
            List<Authenticator> list = HttpRequestGlobalConfig.get().getAuthentications();

            int count = 0;
            for (Authenticator basicAuthentication : list) {
                if (basicAuthentication.getKeyName().equals(value)) {
                    count++;
                }
            }

            if (count > 1) {
                return FormValidation.error("The Key Name must be unique");
            }

            return FormValidation.validateRequired(value);

        }

        public static List<Range<Integer>> parseToRange(String value) {
            List<Range<Integer>> validRanges = new ArrayList<Range<Integer>>();

            String[] codes = value.split(",");
            for (String code : codes) {
                String[] fromTo = code.trim().split(":");
                checkArgument(fromTo.length <= 2, "Code %s should be an interval from:to or a single value", code);

                Integer from;
                try {
                    from = Integer.parseInt(fromTo[0]);
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("Invalid number "+fromTo[0]);
                }

                Integer to = from;
                if (fromTo.length != 1) {
                    try {
                        to = Integer.parseInt(fromTo[1]);
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException("Invalid number "+fromTo[1]);
                    }
                }

                checkArgument(from <= to, "Interval %s should be FROM less than TO", code);
                validRanges.add(Ranges.closed(from, to));
            }

            return validRanges;
        }

        public FormValidation doCheckValidResponseCodes(@QueryParameter String value) {
            return checkValidResponseCodes(value);
        }

        public static FormValidation checkValidResponseCodes(String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            try {
                parseToRange(value);
            } catch (IllegalArgumentException iae) {
                return FormValidation.error("Response codes expected is wrong. "+iae.getMessage());
            }
            return FormValidation.ok();

        }
    }

}
