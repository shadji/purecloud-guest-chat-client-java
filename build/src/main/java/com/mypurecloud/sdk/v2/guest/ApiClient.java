package com.mypurecloud.sdk.v2.guest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.net.Proxy;
import java.text.DateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import javax.xml.bind.DatatypeConverter;

import com.google.common.util.concurrent.SettableFuture;

import com.mypurecloud.sdk.v2.guest.auth.ApiKeyAuth;
import com.mypurecloud.sdk.v2.guest.auth.Authentication;
import com.mypurecloud.sdk.v2.guest.auth.OAuth;
import com.mypurecloud.sdk.v2.guest.connector.*;
import com.mypurecloud.sdk.v2.guest.extensions.AuthResponse;
import com.mypurecloud.sdk.v2.guest.PureCloudRegionHosts;


public class ApiClient implements AutoCloseable {
    private static final String DEFAULT_BASE_PATH = "https://api.mypurecloud.com";
    private static final String DEFAULT_USER_AGENT = "PureCloud Guest Chat SDK/java";
    private static final String USER_AGENT_HEADER = "User-Agent";

    private static Map<String, Authentication> buildAuthentications() {
        Map<String, Authentication> authentications = new HashMap<>();
        authentications.put("PureCloud OAuth", new OAuth());
        authentications.put("Guest Chat JWT", new ApiKeyAuth("header", "Authorization")  { { setApiKeyPrefix("Bearer"); } });

        return Collections.unmodifiableMap(authentications);
    }

    private final Map<String, String> defaultHeaderMap;
    private final String basePath;
    private final Boolean shouldThrowErrors;

    private final DateFormat dateFormat;
    private final ObjectMapper objectMapper;

    private final ConnectorProperties properties;

    private final Map<String, Authentication> authentications;
    private final ApiClientConnector connector;

    public ApiClient() {
        this(Builder.standard());
    }

    private ApiClient(Builder builder) {
        String basePath = builder.basePath;
        if (basePath == null) {
            basePath = DEFAULT_BASE_PATH;
        }
        this.basePath = basePath;

        this.defaultHeaderMap = new HashMap<>(builder.defaultHeaderMap);
        this.properties = builder.properties.copy();
        this.shouldThrowErrors = builder.shouldThrowErrors == null ? true : builder.shouldThrowErrors;

        DateFormat dateFormat = builder.dateFormat;
        if (dateFormat == null) {
            dateFormat = buildDateFormat();
        }
        this.dateFormat = dateFormat;

        ObjectMapper objectMapper = builder.objectMapper;
        if (objectMapper == null) {
            objectMapper = buildObjectMapper(dateFormat);
        }
        this.objectMapper = objectMapper;

        this.authentications = buildAuthentications(builder);

        this.connector = buildHttpConnector(builder);
    }

    @Override
    public void close() throws Exception {
        connector.close();
    }

    public static ObjectMapper buildObjectMapper(DateFormat dateFormat) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        objectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        objectMapper.registerModule(new JodaModule());
        objectMapper.setDateFormat(dateFormat);
        return objectMapper;
    }

    private DateFormat buildDateFormat() {
        return new ApiDateFormat();
    }

    private Map<String, Authentication> buildAuthentications(Builder builder) {
        Map<String, Authentication> authentications = buildAuthentications();
        String accessToken = builder.accessToken;
        for (Authentication authentication : authentications.values()) {
            if (authentication instanceof OAuth && accessToken != null) {
                ((OAuth)authentication).setAccessToken(accessToken);
            }
        }
        return authentications;
    }

    private ApiClientConnector buildHttpConnector(Builder builder) {
        return ApiClientConnectorLoader.load(properties);
    }

    public boolean getShouldThrowErrors() {
        return shouldThrowErrors;
    }

    public String getBasePath() {
        return basePath;
    }

    public ObjectMapper getObjectMapper() {
        return this.objectMapper;
    }

    /**
     * Helper method to set access token for the first OAuth2 authentication.
     */
    public void setAccessToken(String accessToken) {
        for (Authentication auth : authentications.values()) {
            if (auth instanceof OAuth) {
                ((OAuth) auth).setAccessToken(accessToken);
                return;
            }
        }
        throw new RuntimeException("No OAuth2 authentication configured!");
    }

    /**
     * Helper method to set access token for the first OAuth2 authentication.
     */
    public void setJwt(String jwt) {
        for (Authentication auth : authentications.values()) {
            if (auth instanceof ApiKeyAuth) {
                ((ApiKeyAuth) auth).setApiKey(jwt);
                return;
            }
        }
        throw new RuntimeException("No OAuth2 authentication configured!");
    }

    /**
     * Connect timeout (in milliseconds).
     */
    public int getConnectTimeout() {
        return properties.getProperty(ApiClientConnectorProperty.CONNECTION_TIMEOUT, Integer.class, 0);
    }

    /**
     * Parse the given string into Date object.
     */
    public Date parseDate(String str) {
        try {
            synchronized (dateFormat) {
                return dateFormat.parse(str);
            }
        } catch (java.text.ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Format the given Date object into string.
     */
    public String formatDate(Date date) {
        synchronized (dateFormat) {
            return dateFormat.format(date);
        }
    }

    /**
     * Format the given parameter object into string.
     */
    public String parameterToString(Object param) {
        if (param == null) {
            return "";
        } else if (param instanceof Date) {
            return formatDate((Date) param);
        } else if (param instanceof Collection) {
            StringBuilder b = new StringBuilder();
            for(Object o : (Collection<?>)param) {
                if(b.length() > 0) {
                    b.append(",");
                }
                b.append(String.valueOf(o));
            }
            return b.toString();
        } else {
            return String.valueOf(param);
        }
    }

    /*
      Format to {@code Pair} objects.
    */
    public List<Pair> parameterToPairs(String collectionFormat, String name, Object value){
        List<Pair> params = new ArrayList<Pair>();

        // preconditions
        if (name == null || name.isEmpty() || value == null) return params;

        Collection<?> valueCollection = null;
        if (value instanceof Collection<?>) {
            valueCollection = (Collection<?>) value;
        } else {
            params.add(new Pair(name, parameterToString(value)));
            return params;
        }

        if (valueCollection.isEmpty()){
            return params;
        }

        // get the collection format
        collectionFormat = (collectionFormat == null || collectionFormat.isEmpty() ? "csv" : collectionFormat); // default: csv

        // create the params based on the collection format
        if (collectionFormat.equals("multi")) {
            for (Object item : valueCollection) {
                params.add(new Pair(name, parameterToString(item)));
            }

            return params;
        }

        String delimiter = ",";

        if (collectionFormat.equals("csv")) {
            delimiter = ",";
        } else if (collectionFormat.equals("ssv")) {
            delimiter = " ";
        } else if (collectionFormat.equals("tsv")) {
            delimiter = "\t";
        } else if (collectionFormat.equals("pipes")) {
            delimiter = "|";
        }

        StringBuilder sb = new StringBuilder() ;
        for (Object item : valueCollection) {
            sb.append(delimiter);
            sb.append(parameterToString(item));
        }

        params.add(new Pair(name, sb.substring(1)));

        return params;
    }

    /**
     * Check if the given MIME is a JSON MIME.
     * JSON MIME examples:
     *   application/json
     *   application/json; charset=UTF8
     *   APPLICATION/JSON
     */
    public boolean isJsonMime(String mime) {
        return mime != null && mime.matches("(?i)application\\/json(;.*)?");
    }

    /**
     * Select the Accept header's value from the given accepts array:
     *   if JSON exists in the given array, use it;
     *   otherwise use all of them (joining into a string)
     *
     * @param accepts The accepts array to select from
     * @return The Accept header to use. If the given array is empty,
     *   null will be returned (not to set the Accept header explicitly).
     */
    public String selectHeaderAccept(String[] accepts) {
        if (accepts.length == 0) {
            return null;
        }
        for (String accept : accepts) {
            if (isJsonMime(accept)) {
                return accept;
            }
        }
        return StringUtil.join(accepts, ",");
    }

    /**
     * Select the Content-Type header's value from the given array:
     *   if JSON exists in the given array, use it;
     *   otherwise use the first one of the array.
     *
     * @param contentTypes The Content-Type array to select from
     * @return The Content-Type header to use. If the given array is empty,
     *   JSON will be used.
     */
    public String selectHeaderContentType(String[] contentTypes) {
        if (contentTypes.length == 0) {
            return "application/json";
        }
        for (String contentType : contentTypes) {
            if (isJsonMime(contentType)) {
                return contentType;
            }
        }
        return contentTypes[0];
    }

    /**
     * Escape the given string to be used as URL query value.
     */
    public String escapeString(String str) {
        try {
            return URLEncoder.encode(str, "utf8").replaceAll("\\+", "%20");
        } catch (UnsupportedEncodingException e) {
            return str;
        }
    }

/**
     * Serialize the given Java object into string according the given
     * Content-Type (only JSON is supported for now).
     */
    public String serialize(Object obj) throws IOException {
        return objectMapper.writeValueAsString(obj);
    }

    /**
     * Deserialize the string into the provided type
     * 
     * @param obj the string to deserialize
     * @param type the target type for deserialization
     */
    public <T> T deserialize(String obj, Class<T> type) throws IOException {
        return objectMapper.readValue(obj, type);
    }

    /**
     * Build full URL by concatenating base path, the given sub path and query parameters.
     *
     * @param path The sub path
     * @param queryParams The query parameters
     * @return The full URL
     */
    private String buildUrl(String path, Map<String, String> pathParams, List<Pair> queryParams, boolean isAuthRequest) {
        path = path.replaceAll("\\{format\\}", "json");
        if (pathParams != null && !pathParams.isEmpty()) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                path = path.replaceAll("\\{" + entry.getKey() + "\\}", entry.getValue());
            }
        }

        final StringBuilder url = new StringBuilder();
        if (isAuthRequest) {
            String[] parts = basePath.split("\\.", 2);
            url.append("https://login.").append(parts[1]).append(path);
        } else {
            url.append(basePath).append(path);
        }

        if (queryParams != null && !queryParams.isEmpty()) {
            // support (constant) query string in `path`, e.g. "/posts?draft=1"
            String prefix = path.contains("?") ? "&" : "?";
            for (Pair param : queryParams) {
                if (param.getValue() != null) {
                    if (prefix != null) {
                        url.append(prefix);
                        prefix = null;
                    } else {
                        url.append("&");
                    }
                    String value = parameterToString(param.getValue());
                    url.append(escapeString(param.getName())).append("=").append(escapeString(value));
                }
            }
        }

        return url.toString();
    }

    private ApiClientConnectorRequest prepareConnectorRequest(final ApiRequest<?> request, boolean isAuthRequest) throws IOException {
        final String path = request.getPath();
        final List<Pair> queryParams = new ArrayList<>(request.getQueryParams());

        // Add headers
        final Map<String, String> headers = new HashMap<>();
        String accept = request.getAccepts();
        if (accept != null && !accept.isEmpty()) {
            headers.put("Accept", accept);
        }
        String contentType = request.getContentType();
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("Content-Type", contentType);
        }
        Map<String, String> headerParams = request.getHeaderParams();
        if (headerParams != null && !headerParams.isEmpty()) {
            for (Map.Entry<String, String> headerParam : headerParams.entrySet()) {
                headers.put(headerParam.getKey(), headerParam.getValue());
            }
        }
        Map<String, String> customHeaders = request.getCustomHeaders();
        if (customHeaders != null && !customHeaders.isEmpty()) {
            for (Map.Entry<String, String> customHeader : customHeaders.entrySet()) {
                headers.put(customHeader.getKey(), customHeader.getValue());
            }
        }
        for (Map.Entry<String, String> defaultHeader : defaultHeaderMap.entrySet()) {
            if (!headers.containsKey(defaultHeader.getKey()))
                headers.put(defaultHeader.getKey(), defaultHeader.getValue());
        }

        updateParamsForAuth(request.getAuthNames(), queryParams, headers);
        final String url = buildUrl(path, request.getPathParams(), queryParams, isAuthRequest);

        final Object body = request.getBody();
        final Map<String, Object> formParams = request.getFormParams();
        final String serializedBody;
        if (body != null && !formParams.isEmpty()) {
            throw new IllegalStateException("Request cannot have both form and body parameters.");
        }
        else if (body != null) {
            serializedBody = serialize(body);
        }
        else if (formParams != null) {
            serializedBody = getXWWWFormUrlencodedParams(formParams);
        }
        else {
            serializedBody = null;
        }

        return new ApiClientConnectorRequest() {
            @Override
            public String getMethod() {
                return request.getMethod();
            }

            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public Map<String, String> getHeaders() {
                return headers;
            }

            @Override
            public boolean hasBody() {
                return (serializedBody != null);
            }

            @Override
            public String readBody() throws IOException {
                return serializedBody;
            }

            @Override
            public InputStream getBody() throws IOException {
                return (serializedBody != null) ? new ByteArrayInputStream(serializedBody.getBytes("UTF8")) : null;
            }
        };
    }

    private <T> ApiResponse<T> interpretConnectorResponse(ApiClientConnectorResponse response, TypeReference<T> returnType) throws ApiException, IOException {
        int statusCode = response.getStatusCode();
        String reasonPhrase = response.getStatusReasonPhrase();
        Map<String, String> headers = response.getHeaders();

        if (statusCode >= 200 && statusCode < 300) {
            String body = null;
            T entity = null;
            if (statusCode != 204 && returnType != null && returnType.getType() != Void.class && response.hasBody()) {
                body = response.readBody();
                if (body != null && body.length() > 0 && returnType.getType() == String.class) {
                    entity = (T)body;
                } else if (body != null && body.length() > 0) {
                    entity = objectMapper.readValue(body, returnType);
                }
            }
            return new ApiResponseWrapper<>(statusCode, reasonPhrase, headers, body, entity);
        }
        else {
            String message = "error";
            String body = response.readBody();
            throw new ApiException(statusCode, message, headers, body);
        }
    }

    private <T> ApiResponse<T> getAPIResponse(ApiRequest<?> request, TypeReference<T> returnType, boolean isAuthRequest) throws IOException, ApiException {
        ApiClientConnectorRequest connectorRequest = prepareConnectorRequest(request, isAuthRequest);
        ApiClientConnectorResponse connectorResponse = null;
        try {
            connectorResponse = connector.invoke(connectorRequest);
            return interpretConnectorResponse(connectorResponse, returnType);
        }
        finally {
            if (connectorResponse != null) {
                try {
                    connectorResponse.close();
                }
                catch (Throwable exception) {
                    throw new RuntimeException(exception);
                }
            }
        }
    }

    private <T> Future<ApiResponse<T>> getAPIResponseAsync(ApiRequest<?> request, final TypeReference<T> returnType, final AsyncApiCallback<ApiResponse<T>> callback) {
        final SettableFuture<ApiResponse<T>> future = SettableFuture.create();
        try {
            ApiClientConnectorRequest connectorRequest = prepareConnectorRequest(request, false);
            connector.invokeAsync(connectorRequest, new AsyncApiCallback<ApiClientConnectorResponse>() {
                @Override
                public void onCompleted(ApiClientConnectorResponse connectorResponse) {
                    try {
                        ApiResponse<T> response;
                        try {
                            response = interpretConnectorResponse(connectorResponse, returnType);
                        }
                        finally {
                            connectorResponse.close();
                        }
                        notifySuccess(future, callback, response);
                    }
                    catch (Throwable exception) {
                        notifyFailure(future, callback, exception);
                    }
                }

                @Override
                public void onFailed(Throwable exception) {
                    notifyFailure(future, callback, exception);
                }
            });
        }
        catch (Throwable exception) {
            notifyFailure(future, callback, exception);
        }
        return future;
    }

    private <T> void notifySuccess(SettableFuture<T> future, AsyncApiCallback<T> callback, T result) {
        if (callback != null) {
            try {
                callback.onCompleted(result);
                future.set(result);
            }
            catch (Throwable exception) {
                future.setException(exception);
            }
        }
        else {
            future.set(result);
        }
    }

    private <T> void notifyFailure(SettableFuture<T> future, AsyncApiCallback<T> callback, Throwable exception) {
        if (callback != null) {
            try {
                callback.onFailed(exception);
                future.setException(exception);
            }
            catch (Throwable callbackException) {
                future.setException(callbackException);
            }
        }
        else {
            future.setException(exception);
        }
    }

    public <T> ApiResponse<T> invoke(ApiRequest<?> request, TypeReference<T> returnType) throws ApiException, IOException {
        return getAPIResponse(request, returnType, false);
    }

    public <T> Future<ApiResponse<T>> invokeAsync(ApiRequest<?> request, TypeReference<T> returnType, AsyncApiCallback<ApiResponse<T>> callback) {
        SettableFuture<ApiResponse<T>> future = SettableFuture.create();
        getAPIResponseAsync(request, returnType, callback);
        return future;
    }

    /**
     * Update query and header parameters based on authentication settings.
     *
     * @param authNames The authentications to apply
     */
    private void updateParamsForAuth(String[] authNames, List<Pair> queryParams, Map<String, String> headerParams) {
        for (String authName : authNames) {
            Authentication auth = authentications.get(authName);
            if (auth == null) throw new RuntimeException("Authentication undefined: " + authName);
            auth.applyToParams(queryParams, headerParams);
        }
    }

    /**
     * Encode the given form parameters as request body.
     */
    private String getXWWWFormUrlencodedParams(Map<String, Object> formParams) {
        StringBuilder formParamBuilder = new StringBuilder();

        for (Entry<String, Object> param : formParams.entrySet()) {
            String valueStr = parameterToString(param.getValue());
            try {
                formParamBuilder.append(URLEncoder.encode(param.getKey(), "utf8"))
                        .append("=")
                        .append(URLEncoder.encode(valueStr, "utf8"));
                formParamBuilder.append("&");
            } catch (UnsupportedEncodingException e) {
                // move on to next
            }
        }

        String encodedFormParams = formParamBuilder.toString();
        if (encodedFormParams.endsWith("&")) {
            encodedFormParams = encodedFormParams.substring(0, encodedFormParams.length() - 1);
        }

        return encodedFormParams;
    }

    public static class Builder {
        public static Builder standard() {
            return new Builder(new ConnectorProperties());
        }

        public static Builder from(ApiClient client) {
            if (client == null) {
                throw new NullPointerException();
            }
            Builder builder = new Builder(client.properties);
            builder.defaultHeaderMap.putAll(client.defaultHeaderMap);
            for (Authentication authentication : client.authentications.values()) {
                if (authentication instanceof OAuth) {
                    builder.accessToken = ((OAuth)authentication).getAccessToken();
                }
            }
            builder.dateFormat = client.dateFormat;
            builder.objectMapper = client.objectMapper;
            builder.basePath = client.basePath;
            builder.shouldThrowErrors = client.shouldThrowErrors;
            return builder;
        }

        public static ApiClient defaultClient() {
            return standard().build();
        }

        private final Map<String, String> defaultHeaderMap = new HashMap<>();
        private final ConnectorProperties properties;
        private String accessToken;
        private ObjectMapper objectMapper;
        private DateFormat dateFormat;
        private String basePath;
        private Boolean shouldThrowErrors = true;

        private Builder(ConnectorProperties properties) {
            this.properties = (properties != null) ? properties.copy() : new ConnectorProperties();
            withUserAgent(DEFAULT_USER_AGENT);
            withDefaultHeader("purecloud-sdk", "5.1.0");
        }

        public Builder withDefaultHeader(String header, String value) {
            defaultHeaderMap.put(header, value);
            return this;
        }

        public Builder withAccessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder withUserAgent(String userAgent) {
            return withDefaultHeader(USER_AGENT_HEADER, userAgent);
        }

        public Builder withObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder withDateFormat(DateFormat dateFormat) {
            this.dateFormat = dateFormat;
            return this;
        }

        public Builder withBasePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

         public Builder withBasePath(PureCloudRegionHosts region){      
            this.basePath = region.getApiHost();        
            return this;
        }

        public Builder withConnectionTimeout(int connectionTimeout) {
            properties.setProperty(ApiClientConnectorProperty.CONNECTION_TIMEOUT, connectionTimeout);
            return this;
        }

        public Builder withShouldThrowErrors(boolean shouldThrowErrors) {
            this.shouldThrowErrors = shouldThrowErrors;
            return this;
        }

        public Builder withDetailLevel(DetailLevel detailLevel) {
            properties.setProperty(ApiClientConnectorProperty.DETAIL_LEVEL, detailLevel);
            return this;
        }

        public Builder withProxy(Proxy proxy) {
            properties.setProperty(ApiClientConnectorProperty.PROXY, proxy);
            return this;
        }

        public Builder withProperty(String name, Object value) {
            properties.setProperty(name, value);
            return this;
        }

        public ApiClient build() {
            return new ApiClient(this);
        }
    }

    private static class ConnectorProperties implements ApiClientConnectorProperties {
        private final Map<String, Object> properties;

        public ConnectorProperties() {
            this.properties = new HashMap<>();
        }

        public ConnectorProperties(Map<String, Object> properties) {
            this.properties = new HashMap<>();
            if (properties != null) {
                this.properties.putAll(properties);
            }
        }

        @Override
        public <T> T getProperty(String key, Class<T> propertyClass, T defaultValue) {
            Object value = properties.get(key);
            if (propertyClass.isInstance(value)) {
                return propertyClass.cast(value);
            }
            return defaultValue;
        }

        public void setProperty(String key, Object value) {
            if (key != null) {
                if (value != null) {
                    properties.put(key, value);
                }
                else {
                    properties.remove(key);
                }
            }
        }

        public ConnectorProperties copy() {
            return new ConnectorProperties(properties);
        }
    }

    private static class ApiRequestWrapper<T> implements ApiRequest<T> {
        private final String path;
        private final String method;
        private final List<Pair> queryParams;
        private final T body;
        private final Map<String, String> headerParams;
        private final Map<String, Object> formParams;
        private final String accept;
        private final String contentType;
        private final String[] authNames;

        public ApiRequestWrapper(String path, String method, List<Pair> queryParams, T body, Map<String, String> headerParams, Map<String, Object> formParams, String accept, String contentType, String[] authNames) {
            this.path = path;
            this.method = method;
            this.queryParams = queryParams;
            this.body = body;
            this.headerParams = headerParams;
            this.formParams = formParams;
            this.accept = accept;
            this.contentType = contentType;
            this.authNames = authNames;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public Map<String, String> getPathParams() {
            return Collections.emptyMap();
        }

        @Override
        public List<Pair> getQueryParams() {
            return queryParams;
        }

        @Override
        public Map<String, Object> getFormParams() {
            return formParams;
        }

        @Override
        public Map<String, String> getHeaderParams() {
            return headerParams;
        }

        @Override
        public Map<String, String> getCustomHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getAccepts() {
            return accept;
        }

        @Override
        public T getBody() {
            return body;
        }

        @Override
        public String[] getAuthNames() {
            return authNames;
        }
    }

    private static class ApiResponseWrapper<T> implements ApiResponse<T> {
        private final int statusCode;
        private final String reasonPhrase;
        private final Map<String, String> headers;
        private final String body;
        private final T entity;

        public ApiResponseWrapper(int statusCode, String reasonPhrase, Map<String, String> headers, String body, T entity) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            Map<String, String> caseInsensitiveMap = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            caseInsensitiveMap.putAll(headers);
            this.headers = Collections.unmodifiableMap(caseInsensitiveMap);
            this.body = body;
            this.entity = entity;
        }

        @Override
        public Exception getException() {
            return null;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public String getStatusReasonPhrase() {
            return reasonPhrase;
        }

        @Override
        public boolean hasRawBody() {
            return (body != null && !body.isEmpty());
        }

        @Override
        public String getRawBody() {
            return body;
        }

        @Override
        public T getBody() {
            return entity;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public String getHeader(String key) {
            return headers.get(key);
        }

        @Override
        public String getCorrelationId() {
            return headers.get("ININ-Correlation-ID");
        }

        @Override
        public void close() throws Exception { }
    }
}
