/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.resource;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.support.WebContentGenerator;
import org.springframework.web.util.UrlPathHelper;

/**
 * {@code HttpRequestHandler} that serves static resources in an optimized way
 * according to the guidelines of Page Speed, YSlow, etc.
 *
 * <p>The {@linkplain #setLocations "locations"} property takes a list of Spring
 * {@link Resource} locations from which static resources are allowed to be served
 * by this handler. Resources could be served from a classpath location, e.g.
 * "classpath:/META-INF/public-web-resources/", allowing convenient packaging
 * and serving of resources such as .js, .css, and others in jar files.
 *
 * <p>This request handler may also be configured with a
 * {@link #setResourceResolvers(List) resourcesResolver} and
 * {@link #setResourceTransformers(List) resourceTransformer} chains to support
 * arbitrary resolution and transformation of resources being served. By default
 * a {@link PathResourceResolver} simply finds resources based on the configured
 * "locations". An application can configure additional resolvers and transformers
 * such as the {@link VersionResourceResolver} which can resolve and prepare URLs
 * for resources with a version in the URL.
 *
 * <p>This handler also properly evaluates the {@code Last-Modified} header
 * (if present) so that a {@code 304} status code will be returned as appropriate,
 * avoiding unnecessary overhead for resources that are already cached by the client.
 *
 * @author Keith Donald
 * @author Jeremy Grelle
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @author Brian Clozel
 * @author Rossen Stoyanchev
 * @since 3.0.4
 */
public class ResourceHttpRequestHandler extends WebContentGenerator
		implements HttpRequestHandler, EmbeddedValueResolverAware, InitializingBean, CorsConfigurationSource {

	private static final Log logger = LogFactory.getLog(ResourceHttpRequestHandler.class);

	private static final String URL_RESOURCE_CHARSET_PREFIX = "[charset=";


	private final List<String> locationValues = new ArrayList<>(4);

	/**
	 * 静态资源所在的位置
	 * 我们配置的classpath:/static/js、classpath:/static/css最终会被解析为
	 * Resource对象，代表该文件夹下的所有资源
	 */
	private final List<Resource> locations = new ArrayList<>(4);

	//资源、编码之间的映射
	private final Map<Resource, Charset> locationCharsets = new HashMap<>(4);

	//资源解析器
	private final List<ResourceResolver> resourceResolvers = new ArrayList<>(4);

	//资源转换器
	private final List<ResourceTransformer> resourceTransformers = new ArrayList<>(4);

	//资源解析器链
	@Nullable
	private ResourceResolverChain resolverChain;

	//资源转换器链
	@Nullable
	private ResourceTransformerChain transformerChain;

	//http资源消息转换器，将资源写入到响应体中，所有资源
	@Nullable
	private ResourceHttpMessageConverter resourceHttpMessageConverter;

	//http资源消息转换器，将资源写入到响应体中，部分资源，断点下载
	@Nullable
	private ResourceRegionHttpMessageConverter resourceRegionHttpMessageConverter;

	//内容协商管理器，属性填充阶段自动从容器中填充进来
	@Nullable
	private ContentNegotiationManager contentNegotiationManager;

	//文件扩展名->媒体类型映射
	private final Map<String, MediaType> mediaTypes = new HashMap<>(4);

	@Nullable
	private CorsConfiguration corsConfiguration;

	//路径处理帮助器，属性填充阶段自动填充一个
	@Nullable
	private UrlPathHelper urlPathHelper;

	//值解析器
	@Nullable
	private StringValueResolver embeddedValueResolver;


	public ResourceHttpRequestHandler() {
		super(HttpMethod.GET.name(), HttpMethod.HEAD.name());
	}


	/**
	 * An alternative to {@link #setLocations(List)} that accepts a list of
	 * String-based location values, with support for {@link UrlResource}'s
	 * (e.g. files or HTTP URLs) with a special prefix to indicate the charset
	 * to use when appending relative paths. For example
	 * {@code "[charset=Windows-31J]https://example.org/path"}.
	 * @since 4.3.13
	 */
	public void setLocationValues(List<String> locationValues) {
		Assert.notNull(locationValues, "Location values list must not be null");
		this.locationValues.clear();
		this.locationValues.addAll(locationValues);
	}

	/**
	 * Set the {@code List} of {@code Resource} locations to use as sources
	 * for serving static resources.
	 * @see #setLocationValues(List)
	 */
	public void setLocations(List<Resource> locations) {
		Assert.notNull(locations, "Locations list must not be null");
		this.locations.clear();
		this.locations.addAll(locations);
	}

	/**
	 * Return the configured {@code List} of {@code Resource} locations.
	 * <p>Note that if {@link #setLocationValues(List) locationValues} are provided,
	 * instead of loaded Resource-based locations, this method will return
	 * empty until after initialization via {@link #afterPropertiesSet()}.
	 * @see #setLocationValues
	 * @see #setLocations
	 */
	//返回静态资源所在位置的统一资源描述
	public List<Resource> getLocations() {
		return this.locations;
	}

	/**
	 * Configure the list of {@link ResourceResolver ResourceResolvers} to use.
	 * <p>By default {@link PathResourceResolver} is configured. If using this property,
	 * it is recommended to add {@link PathResourceResolver} as the last resolver.
	 */
	public void setResourceResolvers(@Nullable List<ResourceResolver> resourceResolvers) {
		this.resourceResolvers.clear();
		if (resourceResolvers != null) {
			this.resourceResolvers.addAll(resourceResolvers);
		}
	}

	/**
	 * Return the list of configured resource resolvers.
	 */
	public List<ResourceResolver> getResourceResolvers() {
		return this.resourceResolvers;
	}

	/**
	 * Configure the list of {@link ResourceTransformer ResourceTransformers} to use.
	 * <p>By default no transformers are configured for use.
	 */
	public void setResourceTransformers(@Nullable List<ResourceTransformer> resourceTransformers) {
		this.resourceTransformers.clear();
		if (resourceTransformers != null) {
			this.resourceTransformers.addAll(resourceTransformers);
		}
	}

	/**
	 * Return the list of configured resource transformers.
	 */
	public List<ResourceTransformer> getResourceTransformers() {
		return this.resourceTransformers;
	}

	/**
	 * Configure the {@link ResourceHttpMessageConverter} to use.
	 * <p>By default a {@link ResourceHttpMessageConverter} will be configured.
	 * @since 4.3
	 */
	public void setResourceHttpMessageConverter(@Nullable ResourceHttpMessageConverter messageConverter) {
		this.resourceHttpMessageConverter = messageConverter;
	}

	/**
	 * Return the configured resource converter.
	 * @since 4.3
	 */
	@Nullable
	public ResourceHttpMessageConverter getResourceHttpMessageConverter() {
		return this.resourceHttpMessageConverter;
	}

	/**
	 * Configure the {@link ResourceRegionHttpMessageConverter} to use.
	 * <p>By default a {@link ResourceRegionHttpMessageConverter} will be configured.
	 * @since 4.3
	 */
	public void setResourceRegionHttpMessageConverter(@Nullable ResourceRegionHttpMessageConverter messageConverter) {
		this.resourceRegionHttpMessageConverter = messageConverter;
	}

	/**
	 * Return the configured resource region converter.
	 * @since 4.3
	 */
	@Nullable
	public ResourceRegionHttpMessageConverter getResourceRegionHttpMessageConverter() {
		return this.resourceRegionHttpMessageConverter;
	}

	/**
	 * Configure a {@code ContentNegotiationManager} to help determine the
	 * media types for resources being served. If the manager contains a path
	 * extension strategy it will be checked for registered file extension.
	 * @since 4.3
	 * @deprecated as of 5.2.4 in favor of using {@link #setMediaTypes(Map)}
	 * with mappings possibly obtained from
	 * {@link ContentNegotiationManager#getMediaTypeMappings()}.
	 */
	@Deprecated
	public void setContentNegotiationManager(@Nullable ContentNegotiationManager contentNegotiationManager) {
		this.contentNegotiationManager = contentNegotiationManager;
	}

	/**
	 * Return the configured content negotiation manager.
	 * @since 4.3
	 * @deprecated as of 5.2.4.
	 */
	@Nullable
	@Deprecated
	public ContentNegotiationManager getContentNegotiationManager() {
		return this.contentNegotiationManager;
	}

	/**
	 * Add mappings between file extensions, extracted from the filename of a
	 * static {@link Resource}, and corresponding media type  to set on the
	 * response.
	 * <p>Use of this method is typically not necessary since mappings are
	 * otherwise determined via
	 * {@link javax.servlet.ServletContext#getMimeType(String)} or via
	 * {@link MediaTypeFactory#getMediaType(Resource)}.
	 * @param mediaTypes media type mappings
	 * @since 5.2.4
	 */
	public void setMediaTypes(Map<String, MediaType> mediaTypes) {
		mediaTypes.forEach((ext, mediaType) ->
				this.mediaTypes.put(ext.toLowerCase(Locale.ENGLISH), mediaType));
	}

	/**
	 * Return the {@link #setMediaTypes(Map) configured} media types.
	 * @since 5.2.4
	 */
	public Map<String, MediaType> getMediaTypes() {
		return this.mediaTypes;
	}

	/**
	 * Specify the CORS configuration for resources served by this handler.
	 * <p>By default this is not set in which allows cross-origin requests.
	 */
	public void setCorsConfiguration(CorsConfiguration corsConfiguration) {
		this.corsConfiguration = corsConfiguration;
	}

	/**
	 * Return the specified CORS configuration.
	 */
	@Override
	@Nullable
	public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
		return this.corsConfiguration;
	}

	/**
	 * Provide a reference to the {@link UrlPathHelper} used to map requests to
	 * static resources. This helps to derive information about the lookup path
	 * such as whether it is decoded or not.
	 * @since 4.3.13
	 */
	public void setUrlPathHelper(@Nullable UrlPathHelper urlPathHelper) {
		this.urlPathHelper = urlPathHelper;
	}

	/**
	 * The configured {@link UrlPathHelper}.
	 * @since 4.3.13
	 */
	@Nullable
	public UrlPathHelper getUrlPathHelper() {
		return this.urlPathHelper;
	}

	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		this.embeddedValueResolver = resolver;
	}


	/**
	 * 初始化一个默认的资源解析器PathResourceResolver，如果用户未指定的话
	 * 设置资源解析器解析资源的位置
	 * 根据目前拥有的资源解析器构造一个资源解析器链
	 * 根据目前拥有的资源转换器构造一个资源转换器链
	 * 初始化两个http资源消息转换器
	 * ResourceHttpMessageConverter:：写入完整资源
	 * ResourceRegionHttpMessageConverter：写入部分资源
	 * 将内容协商管理器中资源类型->注册媒体类型映射注册到当前处理器中
	 * @throws Exception
	 */
	//处理器初始化
	@Override
	public void afterPropertiesSet() throws Exception {
		resolveResourceLocations();

		if (logger.isWarnEnabled() && CollectionUtils.isEmpty(this.locations)) {
			logger.warn("Locations list is empty. No resources will be served unless a " +
					"custom ResourceResolver is configured as an alternative to PathResourceResolver.");
		}

		//初始化资源解析器
		if (this.resourceResolvers.isEmpty()) {
			this.resourceResolvers.add(new PathResourceResolver());
		}

		//初始化资源解析器允许解析的资源
		initAllowedLocations();

		// Initialize immutable resolver and transformer chains
		//初始化一个资源解析器链
		this.resolverChain = new DefaultResourceResolverChain(this.resourceResolvers);
		//初始化一个资源转换器链
		this.transformerChain = new DefaultResourceTransformerChain(this.resolverChain, this.resourceTransformers);

		//两个http资源消息转换器，负责将静态资源写入响应中
		if (this.resourceHttpMessageConverter == null) {
			this.resourceHttpMessageConverter = new ResourceHttpMessageConverter();
		}
		if (this.resourceRegionHttpMessageConverter == null) {
			this.resourceRegionHttpMessageConverter = new ResourceRegionHttpMessageConverter();
		}

		//获取内容协商管理器
		ContentNegotiationManager manager = getContentNegotiationManager();
		//将内容协商管理器中注册媒体类型映射注册到当前处理器中
		if (manager != null) {
			setMediaTypes(manager.getMediaTypeMappings());
		}

		//空方法，直接忽略
		@SuppressWarnings("deprecation")
		org.springframework.web.accept.PathExtensionContentNegotiationStrategy strategy =
				initContentNegotiationStrategy();
		if (strategy != null) {
			setMediaTypes(strategy.getMediaTypes());
		}
	}

	private void resolveResourceLocations() {
		if (CollectionUtils.isEmpty(this.locationValues)) {
			return;
		}
		else if (!CollectionUtils.isEmpty(this.locations)) {
			throw new IllegalArgumentException("Please set either Resource-based \"locations\" or " +
					"String-based \"locationValues\", but not both.");
		}

		ApplicationContext applicationContext = obtainApplicationContext();
		for (String location : this.locationValues) {
			if (this.embeddedValueResolver != null) {
				String resolvedLocation = this.embeddedValueResolver.resolveStringValue(location);
				if (resolvedLocation == null) {
					throw new IllegalArgumentException("Location resolved to null: " + location);
				}
				location = resolvedLocation;
			}
			Charset charset = null;
			location = location.trim();
			if (location.startsWith(URL_RESOURCE_CHARSET_PREFIX)) {
				int endIndex = location.indexOf(']', URL_RESOURCE_CHARSET_PREFIX.length());
				if (endIndex == -1) {
					throw new IllegalArgumentException("Invalid charset syntax in location: " + location);
				}
				String value = location.substring(URL_RESOURCE_CHARSET_PREFIX.length(), endIndex);
				charset = Charset.forName(value);
				location = location.substring(endIndex + 1);
			}
			Resource resource = applicationContext.getResource(location);
			this.locations.add(resource);
			if (charset != null) {
				if (!(resource instanceof UrlResource)) {
					throw new IllegalArgumentException("Unexpected charset for non-UrlResource: " + resource);
				}
				this.locationCharsets.put(resource, charset);
			}
		}
	}

	/**
	 * Look for a {@code PathResourceResolver} among the configured resource
	 * resolvers and set its {@code allowedLocations} property (if empty) to
	 * match the {@link #setLocations locations} configured on this class.
	 */
	//初始化资源解析器允许解析的资源
	protected void initAllowedLocations() {
		//用户未在<mvc:resource >中配置静态资源的位置
		if (CollectionUtils.isEmpty(this.locations)) {
			return;
		}
		//遍历所有的资源解析器
		for (int i = getResourceResolvers().size() - 1; i >= 0; i--) {
			if (getResourceResolvers().get(i) instanceof PathResourceResolver) {
				PathResourceResolver pathResolver = (PathResourceResolver) getResourceResolvers().get(i);
				//设置资源解析器允许解析的资源
				if (ObjectUtils.isEmpty(pathResolver.getAllowedLocations())) {
					pathResolver.setAllowedLocations(getLocations().toArray(new Resource[0]));
				}
				if (this.urlPathHelper != null) {
					//设置资源编码
					pathResolver.setLocationCharsets(this.locationCharsets);
					//设置路径处理帮助器
					pathResolver.setUrlPathHelper(this.urlPathHelper);
				}
				break;
			}
		}
	}

	/**
	 * Initialize the strategy to use to determine the media type for a resource.
	 * @deprecated as of 5.2.4 this method returns {@code null}, and if a
	 * sub-class returns an actual instance,the instance is used only as a
	 * source of media type mappings, if it contains any. Please, use
	 * {@link #setMediaTypes(Map)} instead, or if you need to change behavior,
	 * you can override {@link #getMediaType(HttpServletRequest, Resource)}.
	 */
	@Nullable
	@Deprecated
	@SuppressWarnings("deprecation")
	protected org.springframework.web.accept.PathExtensionContentNegotiationStrategy initContentNegotiationStrategy() {
		return null;
	}

	/**
	 * Processes a resource request.
	 * <p>Checks for the existence of the requested resource in the configured list of locations.
	 * If the resource does not exist, a {@code 404} response will be returned to the client.
	 * If the resource exists, the request will be checked for the presence of the
	 * {@code Last-Modified} header, and its value will be compared against the last-modified
	 * timestamp of the given resource, returning a {@code 304} status code if the
	 * {@code Last-Modified} value  is greater. If the resource is newer than the
	 * {@code Last-Modified} value, or the header is not present, the content resource
	 * of the resource will be written to the response with caching headers
	 * set to expire one year in the future.
	 */
	//完成静态资源请求处理
	@Override
	public void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// For very general mappings (e.g. "/") we need to check 404 first
		//检查能否根据路径加载到静态资源
		Resource resource = getResource(request);
		if (resource == null) {
			logger.debug("Resource not found");
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		//OPTIONS类型请求设置允许访问后直接跳过处理
		if (HttpMethod.OPTIONS.matches(request.getMethod())) {
			response.setHeader("Allow", getAllowHeader());
			return;
		}

		// Supported methods and required session
		//检查是否支持处理本次请求
		checkRequest(request);

		// Header phase
		if (new ServletWebRequest(request, response).checkNotModified(resource.lastModified())) {
			logger.trace("Resource not modified");
			return;
		}

		// Apply cache settings, if any
		//设置静态资源的缓存时间
		prepareResponse(response);

		// Check the media type for the resource
		//获取资源对应的媒体类型
		MediaType mediaType = getMediaType(request, resource);
		//设置响应资源的内容类型和大小
		setHeaders(response, resource, mediaType);

		// Content phase
		ServletServerHttpResponse outputMessage = new ServletServerHttpResponse(response);
		//未设置Range请求头表示需要全部的静态资源
		if (request.getHeader(HttpHeaders.RANGE) == null) {
			Assert.state(this.resourceHttpMessageConverter != null, "Not initialized");
			//将资源写入过程委派给了ResourceHttpMessageConverter完成
			this.resourceHttpMessageConverter.write(resource, mediaType, outputMessage);
		}
		/**
		 * 设置了Range请求头表示只需要部分资源，即断点下载功能
		 * Range请求头中会指定需要资源的开始和结束的索引位置
		 * 此处只需要Range请求头中指定索引位置的静态资源
		 */
		else {
			Assert.state(this.resourceRegionHttpMessageConverter != null, "Not initialized");
			ServletServerHttpRequest inputMessage = new ServletServerHttpRequest(request);
			try {
				//得到Range请求头的内容
				List<HttpRange> httpRanges = inputMessage.getHeaders().getRange();
				//设置响应状态码为206
				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				//只写入指定位置和大小的静态资源
				this.resourceRegionHttpMessageConverter.write(
						HttpRange.toResourceRegions(httpRanges, resource), mediaType, outputMessage);
			}
			catch (IllegalArgumentException ex) {
				response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.contentLength());
				//设置错误状态码416
				response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			}
		}
	}

	/**
	 * 替换路径中使用不正确的斜杠
	 * 替换完成后检查路径是否有效
	 * 路径中是否使用了无效的转义字符
	 * 经过上述3步验证之后，资源解析器根据请求路径解析加载对应位置下的资源
	 * @param request
	 * @return
	 * @throws IOException
	 */
	@Nullable
	protected Resource getResource(HttpServletRequest request) throws IOException {
		/**
		 * org.springframework.web.servlet.HandlerMapping.pathWithinHandlerMapping
		 * 得到保存在请求域属性中的路径,这个路径是在PathExposingHandlerInterceptor中设置
		 * 进去的
		 */
		String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		if (path == null) {
			throw new IllegalStateException("Required request attribute '" +
					HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE + "' is not set");
		}

		/**
		 * 解决路径中斜杠不正确的问题
		 * 将路径中的双斜杠替换为单斜杠
		 * 反斜杠替换为正斜杠
		 */
		path = processPath(path);
		/**
		 * isInvalidPath()方法，从3个方面检查请求路径是不是一个无效的路径
		 * 1.路径中包含"WEB-INF"或者 "META-INF"不是一个无效路径
		 * 2."../"这种的路径也不是无效路径
		 * 3.ResourceUtils类isUrl()方法判断是不是一个无效路径
		 * 经过这3个检验通过之后，就是一个无效路径了
		 */
		if (!StringUtils.hasText(path) || isInvalidPath(path)) {
			return null;
		}
		//检查给定路径是否包含无效的转义序列
		if (isInvalidEncodedPath(path)) {
			return null;
		}

		Assert.notNull(this.resolverChain, "ResourceResolverChain not initialized.");
		Assert.notNull(this.transformerChain, "ResourceTransformerChain not initialized.");

		/**
		 * 根据路径加载资源
		 * getLocations()方法，获取统一资源描述，这里是ClassPathResource
		 */
		Resource resource = this.resolverChain.resolveResource(request, path, getLocations());
		if (resource != null) {
			resource = this.transformerChain.transform(request, resource);
		}
		return resource;
	}

	/**
	 * Process the given resource path.
	 * <p>The default implementation replaces:
	 * <ul>
	 * <li>Backslash with forward slash.
	 * <li>Duplicate occurrences of slash with a single slash.
	 * <li>Any combination of leading slash and control characters (00-1F and 7F)
	 * with a single "/" or "". For example {@code "  / // foo/bar"}
	 * becomes {@code "/foo/bar"}.
	 * </ul>
	 * @since 3.2.12
	 */
	protected String processPath(String path) {
		path = StringUtils.replace(path, "\\", "/");
		path = cleanDuplicateSlashes(path);
		return cleanLeadingSlash(path);
	}

	private String cleanDuplicateSlashes(String path) {
		StringBuilder sb = null;
		char prev = 0;
		for (int i = 0; i < path.length(); i++) {
			char curr = path.charAt(i);
			try {
				if ((curr == '/') && (prev == '/')) {
					if (sb == null) {
						sb = new StringBuilder(path.substring(0, i));
					}
					continue;
				}
				if (sb != null) {
					sb.append(path.charAt(i));
				}
			}
			finally {
				prev = curr;
			}
		}
		return sb != null ? sb.toString() : path;
	}

	private String cleanLeadingSlash(String path) {
		boolean slash = false;
		for (int i = 0; i < path.length(); i++) {
			if (path.charAt(i) == '/') {
				slash = true;
			}
			else if (path.charAt(i) > ' ' && path.charAt(i) != 127) {
				if (i == 0 || (i == 1 && slash)) {
					return path;
				}
				return (slash ? "/" + path.substring(i) : path.substring(i));
			}
		}
		return (slash ? "/" : "");
	}

	/**
	 * Check whether the given path contains invalid escape sequences.
	 * @param path the path to validate
	 * @return {@code true} if the path is invalid, {@code false} otherwise
	 */
	private boolean isInvalidEncodedPath(String path) {
		if (path.contains("%")) {
			try {
				// Use URLDecoder (vs UriUtils) to preserve potentially decoded UTF-8 chars
				String decodedPath = URLDecoder.decode(path, "UTF-8");
				if (isInvalidPath(decodedPath)) {
					return true;
				}
				decodedPath = processPath(decodedPath);
				if (isInvalidPath(decodedPath)) {
					return true;
				}
			}
			catch (IllegalArgumentException ex) {
				// May not be possible to decode...
			}
			catch (UnsupportedEncodingException ex) {
				// Should never happen...
			}
		}
		return false;
	}

	/**
	 * Identifies invalid resource paths. By default rejects:
	 * <ul>
	 * <li>Paths that contain "WEB-INF" or "META-INF"
	 * <li>Paths that contain "../" after a call to
	 * {@link org.springframework.util.StringUtils#cleanPath}.
	 * <li>Paths that represent a {@link org.springframework.util.ResourceUtils#isUrl
	 * valid URL} or would represent one after the leading slash is removed.
	 * </ul>
	 * <p><strong>Note:</strong> this method assumes that leading, duplicate '/'
	 * or control characters (e.g. white space) have been trimmed so that the
	 * path starts predictably with a single '/' or does not have one.
	 * @param path the path to validate
	 * @return {@code true} if the path is invalid, {@code false} otherwise
	 * @since 3.0.6
	 */
	protected boolean isInvalidPath(String path) {
		if (path.contains("WEB-INF") || path.contains("META-INF")) {
			if (logger.isWarnEnabled()) {
				logger.warn(LogFormatUtils.formatValue(
						"Path with \"WEB-INF\" or \"META-INF\": [" + path + "]", -1, true));
			}
			return true;
		}
		if (path.contains(":/")) {
			String relativePath = (path.charAt(0) == '/' ? path.substring(1) : path);
			if (ResourceUtils.isUrl(relativePath) || relativePath.startsWith("url:")) {
				if (logger.isWarnEnabled()) {
					logger.warn(LogFormatUtils.formatValue(
							"Path represents URL or has \"url:\" prefix: [" + path + "]", -1, true));
				}
				return true;
			}
		}
		if (path.contains("..") && StringUtils.cleanPath(path).contains("../")) {
			if (logger.isWarnEnabled()) {
				logger.warn(LogFormatUtils.formatValue(
						"Path contains \"../\" after call to StringUtils#cleanPath: [" + path + "]", -1, true));
			}
			return true;
		}
		return false;
	}

	/**
	 * Determine the media type for the given request and the resource matched
	 * to it. This implementation tries to determine the MediaType using one of
	 * the following lookups based on the resource filename and its path
	 * extension:
	 * <ol>
	 * <li>{@link javax.servlet.ServletContext#getMimeType(String)}
	 * <li>{@link #getMediaTypes()}
	 * <li>{@link MediaTypeFactory#getMediaType(String)}
	 * </ol>
	 * @param request the current request
	 * @param resource the resource to check
	 * @return the corresponding media type, or {@code null} if none found
	 */
	//获取资源对应的媒体类型
	@Nullable
	protected MediaType getMediaType(HttpServletRequest request, Resource resource) {
		MediaType result = null;
		/**
		 * mimeType = application/javascript
		 * resource.getFilename()方法， 获取当前请求资源的名字
		 * 该方法会得到它要响应的资源的内容类型
		 */
		String mimeType = request.getServletContext().getMimeType(resource.getFilename());
		if (StringUtils.hasText(mimeType)) {
			//转化为媒体类型
			result = MediaType.parseMediaType(mimeType);
		}
		/**
		 * 如果Servlet上下文不能识别资源的内容类型
		 * 那么就在此处进一步处理，根据文件的扩展名来获取最终响应的媒体类型
		 */
		if (result == null || MediaType.APPLICATION_OCTET_STREAM.equals(result)) {
			MediaType mediaType = null;
			String filename = resource.getFilename();
			//文件扩展名
			String ext = StringUtils.getFilenameExtension(filename);
			if (ext != null) {
				//判断是不是xml或json
				mediaType = this.mediaTypes.get(ext.toLowerCase(Locale.ENGLISH));
			}
			//通过媒体类型工厂获取后缀名对应的媒体类型
			if (mediaType == null) {
				mediaType = MediaTypeFactory.getMediaType(filename).orElse(null);
			}
			if (mediaType != null) {
				result = mediaType;
			}
		}
		return result;
	}

	/**
	 * Set headers on the given servlet response.
	 * Called for GET requests as well as HEAD requests.
	 * @param response current servlet response
	 * @param resource the identified resource (never {@code null})
	 * @param mediaType the resource's media type (never {@code null})
	 * @throws IOException in case of errors while setting the headers
	 */
	//设置响应资源的内容类型和大小
	protected void setHeaders(HttpServletResponse response, Resource resource, @Nullable MediaType mediaType)
			throws IOException {

		//设置资源的大小
		long length = resource.contentLength();
		if (length > Integer.MAX_VALUE) {
			response.setContentLengthLong(length);
		}
		else {
			response.setContentLength((int) length);
		}

		//设置响应的内容类型
		if (mediaType != null) {
			response.setContentType(mediaType.toString());
		}

		/**
		 * HttpResource是Resource的扩展接口
		 * 代表一个需要写入响应的资源，接口只有一个方法getResponseHeaders()
		 * 代表为该资源设置的响应头
		 */
		if (resource instanceof HttpResource) {
			HttpHeaders resourceHeaders = ((HttpResource) resource).getResponseHeaders();
			resourceHeaders.forEach((headerName, headerValues) -> {
				boolean first = true;
				for (String headerValue : headerValues) {
					if (first) {
						response.setHeader(headerName, headerValue);
					}
					else {
						response.addHeader(headerName, headerValue);
					}
					first = false;
				}
			});
		}

		/**
		 * "Accept-Ranges"
		 * 代表了该服务器可以接受范围请求，即我们常见的断点下载功能
		 * 可以通过请求头Range来定义获取的字节范围，格式为 bytes=0-8，那么只会获取这个范围的字节数据
		 */
		response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
	}


	@Override
	public String toString() {
		return "ResourceHttpRequestHandler " + formatLocations();
	}

	private Object formatLocations() {
		if (!this.locationValues.isEmpty()) {
			return this.locationValues.stream().collect(Collectors.joining("\", \"", "[\"", "\"]"));
		}
		else if (!this.locations.isEmpty()) {
			return this.locations;
		}
		return Collections.emptyList();
	}

}
