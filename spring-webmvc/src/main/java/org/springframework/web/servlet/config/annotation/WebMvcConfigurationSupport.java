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

package org.springframework.web.servlet.config.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.servlet.ServletContext;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.cbor.MappingJackson2CborHttpMessageConverter;
import org.springframework.http.converter.feed.AtomFeedHttpMessageConverter;
import org.springframework.http.converter.feed.RssChannelHttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.JsonbHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.smile.MappingJackson2SmileHttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.PathMatcher;
import org.springframework.validation.Errors;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.Validator;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.ConfigurableWebBindingInitializer;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.support.CompositeUriComponentsContributor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.function.support.HandlerFunctionAdapter;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.handler.ConversionServiceExposingInterceptor;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter;
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.JsonViewRequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.JsonViewResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.servlet.resource.ResourceUrlProviderExposingInterceptor;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.ViewResolverComposite;
import org.springframework.web.util.UrlPathHelper;

/**
 * This is the main class providing the configuration behind the MVC Java config.
 * It is typically imported by adding {@link EnableWebMvc @EnableWebMvc} to an
 * application {@link Configuration @Configuration} class. An alternative more
 * advanced option is to extend directly from this class and override methods as
 * necessary, remembering to add {@link Configuration @Configuration} to the
 * subclass and {@link Bean @Bean} to overridden {@link Bean @Bean} methods.
 * For more details see the javadoc of {@link EnableWebMvc @EnableWebMvc}.
 *
 * <p>This class registers the following {@link HandlerMapping HandlerMappings}:</p>
 * <ul>
 * <li>{@link RequestMappingHandlerMapping}
 * ordered at 0 for mapping requests to annotated controller methods.
 * <li>{@link HandlerMapping}
 * ordered at 1 to map URL paths directly to view names.
 * <li>{@link BeanNameUrlHandlerMapping}
 * ordered at 2 to map URL paths to controller bean names.
 * <li>{@link HandlerMapping}
 * ordered at {@code Integer.MAX_VALUE-1} to serve static resource requests.
 * <li>{@link HandlerMapping}
 * ordered at {@code Integer.MAX_VALUE} to forward requests to the default servlet.
 * </ul>
 *
 * <p>Registers these {@link HandlerAdapter HandlerAdapters}:
 * <ul>
 * <li>{@link RequestMappingHandlerAdapter}
 * for processing requests with annotated controller methods.
 * <li>{@link HttpRequestHandlerAdapter}
 * for processing requests with {@link HttpRequestHandler HttpRequestHandlers}.
 * <li>{@link SimpleControllerHandlerAdapter}
 * for processing requests with interface-based {@link Controller Controllers}.
 * </ul>
 *
 * <p>Registers a {@link HandlerExceptionResolverComposite} with this chain of
 * exception resolvers:
 * <ul>
 * <li>{@link ExceptionHandlerExceptionResolver} for handling exceptions through
 * {@link org.springframework.web.bind.annotation.ExceptionHandler} methods.
 * <li>{@link ResponseStatusExceptionResolver} for exceptions annotated with
 * {@link org.springframework.web.bind.annotation.ResponseStatus}.
 * <li>{@link DefaultHandlerExceptionResolver} for resolving known Spring
 * exception types
 * </ul>
 *
 * <p>Registers an {@link AntPathMatcher} and a {@link UrlPathHelper}
 * to be used by:
 * <ul>
 * <li>the {@link RequestMappingHandlerMapping},
 * <li>the {@link HandlerMapping} for ViewControllers
 * <li>and the {@link HandlerMapping} for serving resources
 * </ul>
 * Note that those beans can be configured with a {@link PathMatchConfigurer}.
 *
 * <p>Both the {@link RequestMappingHandlerAdapter} and the
 * {@link ExceptionHandlerExceptionResolver} are configured with default
 * instances of the following by default:
 * <ul>
 * <li>a {@link ContentNegotiationManager}
 * <li>a {@link DefaultFormattingConversionService}
 * <li>an {@link org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean}
 * if a JSR-303 implementation is available on the classpath
 * <li>a range of {@link HttpMessageConverter HttpMessageConverters} depending on the third-party
 * libraries available on the classpath.
 * </ul>
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Sebastien Deleuze
 * @since 3.1
 * @see EnableWebMvc
 * @see WebMvcConfigurer
 */

/**
 * 使用@EnableWebMvc或<mvc:annotation-driven>一共会向容器中注册20个组件，分别如下所示
 *
 * requestMappingHandlerMapping->RequestMappingHandlerMapping
 * mvcPathMatcher->PathMatcher
 * mvcUrlPathHelper->UrlPathHelper
 * mvcContentNegotiationManager->ContentNegotiationManager
 * viewControllerHandlerMapping->SimpleUrlHandlerMapping
 * beanNameHandlerMapping->BeanNameUrlHandlerMapping
 * routerFunctionMapping->RouterFunctionMapping
 * resourceHandlerMapping->SimpleUrlHandlerMapping
 * mvcResourceUrlProvider->ResourceUrlProvider
 * defaultServletHandlerMapping->SimpleUrlHandlerMapping
 * requestMappingHandlerAdapter->RequestMappingHandlerAdapter
 * handlerFunctionAdapter->HandlerFunctionAdapter
 * mvcConversionService->FormattingConversionService
 * mvcValidator->Validator
 * mvcUriComponentsContributor->CompositeUriComponentsContributor
 * httpRequestHandlerAdapter->HttpRequestHandlerAdapter
 * simpleControllerHandlerAdapter->SimpleControllerHandlerAdapter
 * handlerExceptionResolver->HandlerExceptionResolverComposite
 * mvcViewResolver->ViewResolverComposite
 * mvcHandlerMappingIntrospector->HandlerMappingIntrospector
 */
public class WebMvcConfigurationSupport implements ApplicationContextAware, ServletContextAware {

	private static final boolean romePresent;

	private static final boolean jaxb2Present;

	private static final boolean jackson2Present;

	private static final boolean jackson2XmlPresent;

	private static final boolean jackson2SmilePresent;

	private static final boolean jackson2CborPresent;

	private static final boolean gsonPresent;

	private static final boolean jsonbPresent;

	/**
	 * 这些静态字段是一些标志量，通过ClassUtils.isPresent()方法判断是否导入了对应的包，
	 * 如果导入了，就将对应的静态字段置为true，后面就可以根据这些标志量反射实例化对象了。
	 *
	 * 这也解释了为什么只要我们导入了jackson包，
	 * 就可以自动向HandlerAdapter中注册MappingJackson2HttpMessageConverter和MappingJackson2XmlHttpMessageConverter对象
	 */
	static {
		ClassLoader classLoader = WebMvcConfigurationSupport.class.getClassLoader();
		romePresent = ClassUtils.isPresent("com.rometools.rome.feed.WireFeed", classLoader);
		jaxb2Present = ClassUtils.isPresent("javax.xml.bind.Binder", classLoader);
		jackson2Present = ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", classLoader) &&
				ClassUtils.isPresent("com.fasterxml.jackson.core.JsonGenerator", classLoader);
		jackson2XmlPresent = ClassUtils.isPresent("com.fasterxml.jackson.dataformat.xml.XmlMapper", classLoader);
		jackson2SmilePresent = ClassUtils.isPresent("com.fasterxml.jackson.dataformat.smile.SmileFactory", classLoader);
		jackson2CborPresent = ClassUtils.isPresent("com.fasterxml.jackson.dataformat.cbor.CBORFactory", classLoader);
		gsonPresent = ClassUtils.isPresent("com.google.gson.Gson", classLoader);
		jsonbPresent = ClassUtils.isPresent("javax.json.bind.Jsonb", classLoader);
	}


	//web应用上下文
	@Nullable
	private ApplicationContext applicationContext;

	//servlet上下文
	@Nullable
	private ServletContext servletContext;

	//拦截器
	@Nullable
	private List<Object> interceptors;

	//路径匹配配置器
	@Nullable
	private PathMatchConfigurer pathMatchConfigurer;

	//内容协商管理器
	@Nullable
	private ContentNegotiationManager contentNegotiationManager;

	//参数解析器
	@Nullable
	private List<HandlerMethodArgumentResolver> argumentResolvers;

	//返回值处理器
	@Nullable
	private List<HandlerMethodReturnValueHandler> returnValueHandlers;

	//http消息转换器
	@Nullable
	private List<HttpMessageConverter<?>> messageConverters;

	//跨域配置
	@Nullable
	private Map<String, CorsConfiguration> corsConfigurations;


	/**
	 * Set the Spring {@link ApplicationContext}, e.g. for resource loading.
	 */
	@Override
	public void setApplicationContext(@Nullable ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Return the associated Spring {@link ApplicationContext}.
	 * @since 4.2
	 */
	@Nullable
	public final ApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

	/**
	 * Set the {@link javax.servlet.ServletContext}, e.g. for resource handling,
	 * looking up file extensions, etc.
	 */
	@Override
	public void setServletContext(@Nullable ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	/**
	 * Return the associated {@link javax.servlet.ServletContext}.
	 * @since 4.2
	 */
	@Nullable
	public final ServletContext getServletContext() {
		return this.servletContext;
	}


	/**
	 * Return a {@link RequestMappingHandlerMapping} ordered at 0 for mapping
	 * requests to annotated controllers.
	 */
	//注册RequestMappingHandlerMapping

	/**
	 * 为RequestMappingHandlerMapping做了一大堆配置
	 *
	 * 获取用户和系统配置的所有拦截器并注册进去
	 * 获取容器中内容协商管理器并注册进去
	 * 获取用户注册的所有跨域配置并注册进去
	 * 获取路径匹配配置器，然后将路径匹配配置器的配置覆盖进去，主要覆盖一下4中配置
	 * 尾斜杠匹配
	 * 	UrlPathHelper
	 * 	PathMatcher
	 * 	pathPrefixes
	 * @param contentNegotiationManager
	 * @param conversionService
	 * @param resourceUrlProvider
	 * @return
	 */
	@Bean
	@SuppressWarnings("deprecation")
	public RequestMappingHandlerMapping requestMappingHandlerMapping(
			@Qualifier("mvcContentNegotiationManager") ContentNegotiationManager contentNegotiationManager,
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcResourceUrlProvider") ResourceUrlProvider resourceUrlProvider) {

		//创建一个RequestMappingHandlerMapping对象，就是简单的new一个
		RequestMappingHandlerMapping mapping = createRequestMappingHandlerMapping();
		//设置顺序，第一位
		mapping.setOrder(0);
		/**
		 * getInterceptors()方法可以获取到用户注册和系统默认的所有拦截器对象
		 * 然后将这些拦截器全部放入处理器映射器中
		 */
		mapping.setInterceptors(getInterceptors(conversionService, resourceUrlProvider));
		//设置内容协商管理器
		mapping.setContentNegotiationManager(contentNegotiationManager);
		//获取用户注册的所有跨域配置
		mapping.setCorsConfigurations(getCorsConfigurations());

		//获取路径匹配配置器
		PathMatchConfigurer configurer = getPathMatchConfigurer();

		//已经被废弃掉了不推荐使用，直接跳过
		Boolean useSuffixPatternMatch = configurer.isUseSuffixPatternMatch();
		if (useSuffixPatternMatch != null) {
			mapping.setUseSuffixPatternMatch(useSuffixPatternMatch);
		}
		//已经被废弃掉了不推荐使用，直接跳过
		Boolean useRegisteredSuffixPatternMatch = configurer.isUseRegisteredSuffixPatternMatch();
		if (useRegisteredSuffixPatternMatch != null) {
			mapping.setUseRegisteredSuffixPatternMatch(useRegisteredSuffixPatternMatch);
		}

		//是否使用尾斜杠匹配
		Boolean useTrailingSlashMatch = configurer.isUseTrailingSlashMatch();
		if (useTrailingSlashMatch != null) {
			mapping.setUseTrailingSlashMatch(useTrailingSlashMatch);
		}

		//获取路径匹配配置器中设置的UrlPathHelper
		UrlPathHelper pathHelper = configurer.getUrlPathHelper();
		//使用这个UrlPathHelper覆盖默认的UrlPathHelper
		if (pathHelper != null) {
			mapping.setUrlPathHelper(pathHelper);
		}
		//获取在路径匹配配置器中配置的路径匹配器
		PathMatcher pathMatcher = configurer.getPathMatcher();
		//覆盖默认的路径匹配器
		if (pathMatcher != null) {
			mapping.setPathMatcher(pathMatcher);
		}

		//获取配置所有路径前缀
		Map<String, Predicate<Class<?>>> pathPrefixes = configurer.getPathPrefixes();
		if (pathPrefixes != null) {
			mapping.setPathPrefixes(pathPrefixes);
		}

		return mapping;
	}

	/**
	 * Protected method for plugging in a custom subclass of
	 * {@link RequestMappingHandlerMapping}.
	 * @since 4.0
	 */
	protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
		return new RequestMappingHandlerMapping();
	}

	/**
	 * Provide access to the shared handler interceptors used to configure
	 * {@link HandlerMapping} instances with.
	 * <p>This method cannot be overridden; use {@link #addInterceptors} instead.
	 */
	//获取用户注册和系统默认的所有拦截器对象
	protected final Object[] getInterceptors(
			FormattingConversionService mvcConversionService,
			ResourceUrlProvider mvcResourceUrlProvider) {
		if (this.interceptors == null) {
			//拦截器注册中心
			InterceptorRegistry registry = new InterceptorRegistry();
			/**
			 * 该方法被子类重写，会以这个拦截器注册中心为参数调用所有配置类对象中
			 * addInterceptors(registry)方法，用户通过这个注册中心注册拦截器对象。
			 * 很明显，此处调用addInterceptors(registry)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * addInterceptors(registry)方法，将配置类中配置的拦截器注册到拦截器注册中心
			 * 这个注册中心会将所有类型的拦截器统一适配为InterceptorRegistration类型，方便管理
			 */
			addInterceptors(registry);
			//这个拦截器将FormattingConversionService保存到请求域中
			registry.addInterceptor(new ConversionServiceExposingInterceptor(mvcConversionService));
			//这个拦截器将ResourceUrlProvider保存到请求域中
			registry.addInterceptor(new ResourceUrlProviderExposingInterceptor(mvcResourceUrlProvider));
			//获取注册中心中所有的拦截器，会先排序，再返回
			this.interceptors = registry.getInterceptors();
		}
		return this.interceptors.toArray();
	}

	/**
	 * Override this method to add Spring MVC interceptors for
	 * pre- and post-processing of controller invocation.
	 * @see InterceptorRegistry
	 */
	protected void addInterceptors(InterceptorRegistry registry) {
	}

	/**
	 * Callback for building the {@link PathMatchConfigurer}.
	 * Delegates to {@link #configurePathMatch}.
	 * @since 4.1
	 */
	//获取路径匹配配置器
	protected PathMatchConfigurer getPathMatchConfigurer() {
		if (this.pathMatchConfigurer == null) {
			//新建一个路径匹配配置器
			this.pathMatchConfigurer = new PathMatchConfigurer();
			/**
			 * 又是同样的方式，会以这个路径匹配配置器为参数调用所有配置类对象中
			 * configurePathMatch(pathMatchConfigurer)方法，用户通过这个配置器注册路径匹配器。
			 * 很明显，此处调用configurePathMatch(pathMatchConfigurer)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * configurePathMatch(pathMatchConfigurer)方法，将配置类中配置的路径匹配器
			 * 注册到配置器中
			 */
			configurePathMatch(this.pathMatchConfigurer);
		}
		return this.pathMatchConfigurer;
	}

	/**
	 * Override this method to configure path matching options.
	 * @since 4.0.3
	 * @see PathMatchConfigurer
	 */
	protected void configurePathMatch(PathMatchConfigurer configurer) {
	}

	/**
	 * Return a global {@link PathMatcher} instance for path matching
	 * patterns in {@link HandlerMapping HandlerMappings}.
	 * This instance can be configured using the {@link PathMatchConfigurer}
	 * in {@link #configurePathMatch(PathMatchConfigurer)}.
	 * @since 4.1
	 */
	@Bean
	public PathMatcher mvcPathMatcher() {
		/**
		 * getPathMatchConfigurer()方法，获取路径匹配配置器
		 * 然后得到这个配置器中的路径匹配器，把它放入容器中
		 * 如果用户未配置，则使用默认的AntPathMatcher
		 */
		PathMatcher pathMatcher = getPathMatchConfigurer().getPathMatcher();
		return (pathMatcher != null ? pathMatcher : new AntPathMatcher());
	}

	/**
	 * Return a global {@link UrlPathHelper} instance for path matching
	 * patterns in {@link HandlerMapping HandlerMappings}.
	 * This instance can be configured using the {@link PathMatchConfigurer}
	 * in {@link #configurePathMatch(PathMatchConfigurer)}.
	 * @since 4.1
	 */
	@Bean
	public UrlPathHelper mvcUrlPathHelper() {
		/**
		 * getPathMatchConfigurer()方法，获取路径匹配配置器
		 * 然后得到这个配置器中的UrlPathHelper，把它放入容器中
		 * 如果用户未配置，则使用默认的UrlPathHelper
		 */
		UrlPathHelper pathHelper = getPathMatchConfigurer().getUrlPathHelper();
		return (pathHelper != null ? pathHelper : new UrlPathHelper());
	}

	/**
	 * Return a {@link ContentNegotiationManager} instance to use to determine
	 * requested {@linkplain MediaType media types} in a given request.
	 */
	@Bean
	public ContentNegotiationManager mvcContentNegotiationManager() {
		if (this.contentNegotiationManager == null) {
			//创建一个内容协商配置器
			ContentNegotiationConfigurer configurer = new ContentNegotiationConfigurer(this.servletContext);
			//将服务器默认能生产的媒体类型保存到内容协商配置器
			configurer.mediaTypes(getDefaultMediaTypes());
			/**
			 * 和上面拦截器一样，会以这个内容协商配置器为参数调用所有配置类对象中
			 * configureContentNegotiation(configurer)方法，用户通过这个配置器注册和
			 * 修改内容协商管理器。很明显，此处调用configureContentNegotiation(configurer)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * configureContentNegotiation(configurer)方法，
			 * 将配置类中配置的内容协商管理器注册到配置器中
			 */
			configureContentNegotiation(configurer);
			//根据这个配置器的配置创建一个内容协商管理器
			this.contentNegotiationManager = configurer.buildContentNegotiationManager();
		}
		return this.contentNegotiationManager;
	}

	//获取到服务器默认能生产的媒体类型，实际上就判断是否导入了对应包
	protected Map<String, MediaType> getDefaultMediaTypes() {
		Map<String, MediaType> map = new HashMap<>(4);
		if (romePresent) {
			map.put("atom", MediaType.APPLICATION_ATOM_XML);
			map.put("rss", MediaType.APPLICATION_RSS_XML);
		}
		//导了jackson包就能生产xml，json
		if (jaxb2Present || jackson2XmlPresent) {
			map.put("xml", MediaType.APPLICATION_XML);
		}
		if (jackson2Present || gsonPresent || jsonbPresent) {
			map.put("json", MediaType.APPLICATION_JSON);
		}
		if (jackson2SmilePresent) {
			map.put("smile", MediaType.valueOf("application/x-jackson-smile"));
		}
		if (jackson2CborPresent) {
			map.put("cbor", MediaType.APPLICATION_CBOR);
		}
		return map;
	}

	/**
	 * Override this method to configure content negotiation.
	 * @see DefaultServletHandlerConfigurer
	 */
	protected void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
	}

	/**
	 * Return a handler mapping ordered at 1 to map URL paths directly to
	 * view names. To configure view controllers, override
	 * {@link #addViewControllers}.
	 */
	//注册viewControllerHandlerMapping
	@Bean
	@Nullable
	public HandlerMapping viewControllerHandlerMapping(
			@Qualifier("mvcPathMatcher") PathMatcher pathMatcher,
			@Qualifier("mvcUrlPathHelper") UrlPathHelper urlPathHelper,
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcResourceUrlProvider") ResourceUrlProvider resourceUrlProvider) {
		//视图控制注册中心
		ViewControllerRegistry registry = new ViewControllerRegistry(this.applicationContext);
		/**
		 * addViewControllers(registry)这个方法我们应该不陌生，我们经常在配置类中重写该方法，
		 * 注册路径->视图的映射关系。
		 * 此处调用addViewControllers(registry)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * addViewControllers(registry)方法，注册路径->视图的映射关系
		 */
		addViewControllers(registry);

		//真实类型为SimpleUrlHandlerMapping
		AbstractHandlerMapping handlerMapping = registry.buildHandlerMapping();
		if (handlerMapping == null) {
			return null;
		}
		handlerMapping.setPathMatcher(pathMatcher);
		handlerMapping.setUrlPathHelper(urlPathHelper);
		//将所有的拦截器设置进去
		handlerMapping.setInterceptors(getInterceptors(conversionService, resourceUrlProvider));
		//跨域配置
		handlerMapping.setCorsConfigurations(getCorsConfigurations());
		return handlerMapping;
	}

	/**
	 * Override this method to add view controllers.
	 * @see ViewControllerRegistry
	 */
	protected void addViewControllers(ViewControllerRegistry registry) {
	}

	/**
	 * Return a {@link BeanNameUrlHandlerMapping} ordered at 2 to map URL
	 * paths to controller bean names.
	 */
	@Bean
	public BeanNameUrlHandlerMapping beanNameHandlerMapping(
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcResourceUrlProvider") ResourceUrlProvider resourceUrlProvider) {

		BeanNameUrlHandlerMapping mapping = new BeanNameUrlHandlerMapping();
		mapping.setOrder(2);
		mapping.setInterceptors(getInterceptors(conversionService, resourceUrlProvider));
		mapping.setCorsConfigurations(getCorsConfigurations());
		return mapping;
	}

	/**
	 * Return a {@link RouterFunctionMapping} ordered at 3 to map
	 * {@linkplain org.springframework.web.servlet.function.RouterFunction router functions}.
	 * Consider overriding one of these other more fine-grained methods:
	 * <ul>
	 * <li>{@link #addInterceptors} for adding handler interceptors.
	 * <li>{@link #addCorsMappings} to configure cross origin requests processing.
	 * <li>{@link #configureMessageConverters} for adding custom message converters.
	 * </ul>
	 * @since 5.2
	 */
	@Bean
	public RouterFunctionMapping routerFunctionMapping(
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcResourceUrlProvider") ResourceUrlProvider resourceUrlProvider) {

		RouterFunctionMapping mapping = new RouterFunctionMapping();
		mapping.setOrder(3);
		mapping.setInterceptors(getInterceptors(conversionService, resourceUrlProvider));
		mapping.setCorsConfigurations(getCorsConfigurations());
		mapping.setMessageConverters(getMessageConverters());
		return mapping;
	}

	/**
	 * Return a handler mapping ordered at Integer.MAX_VALUE-1 with mapped
	 * resource handlers. To configure resource handling, override
	 * {@link #addResourceHandlers}.
	 */
	@Bean
	@Nullable
	public HandlerMapping resourceHandlerMapping(
			@Qualifier("mvcUrlPathHelper") UrlPathHelper urlPathHelper,
			@Qualifier("mvcPathMatcher") PathMatcher pathMatcher,
			@Qualifier("mvcContentNegotiationManager") ContentNegotiationManager contentNegotiationManager,
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcResourceUrlProvider") ResourceUrlProvider resourceUrlProvider) {

		Assert.state(this.applicationContext != null, "No ApplicationContext set");
		Assert.state(this.servletContext != null, "No ServletContext set");

		//创建资源处理器注册中心
		ResourceHandlerRegistry registry = new ResourceHandlerRegistry(this.applicationContext,
				this.servletContext, contentNegotiationManager, urlPathHelper);
		/**
		 * 会以这个资源处理器注册中心为参数调用所有配置类对象中
		 * addResourceHandlers(registry)方法，用户通过这个资源处理器注册中心设置静态资源的
		 * 路径，静态资源位置，静态资源缓存时间等等。
		 * 很明显，此处调用addResourceHandlers(registry)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * addResourceHandlers(registry)方法，将配置类中静态资源的配置保存到资源处理器注册中心中
		 */
		addResourceHandlers(registry);

		//通过资源处理器注册中心创建处理静态资源的SimpleUrlHandlerMapping
		AbstractHandlerMapping handlerMapping = registry.getHandlerMapping();
		if (handlerMapping == null) {
			return null;
		}
		//设置其它的一些配置
		handlerMapping.setPathMatcher(pathMatcher);
		handlerMapping.setUrlPathHelper(urlPathHelper);
		handlerMapping.setInterceptors(getInterceptors(conversionService, resourceUrlProvider));
		handlerMapping.setCorsConfigurations(getCorsConfigurations());
		return handlerMapping;
	}

	/**
	 * Override this method to add resource handlers for serving static resources.
	 * @see ResourceHandlerRegistry
	 */
	protected void addResourceHandlers(ResourceHandlerRegistry registry) {
	}

	/**
	 * A {@link ResourceUrlProvider} bean for use with the MVC dispatcher.
	 * @since 4.1
	 */
	/**
	 * ResourceUrlProvider是一个监听器，监听ContextRefreshedEvent事件，即refresh()方法执行完成之前触发。
	 * 如果用户配置了静态资源映射，那么就会自动生成一个对应的ResourceHttpRequestHandler静态资源请求处理器。
	 * 而ResourceUrlProvider则会将用户配置的静态资源请求处理器从SimpleUrlHandlerMapping提取出来，单独管理
	 * @return
	 */
	@Bean
	public ResourceUrlProvider mvcResourceUrlProvider() {
		ResourceUrlProvider urlProvider = new ResourceUrlProvider();
		//获取用户在路径匹配配置器配置的UrlPathHelper
		UrlPathHelper pathHelper = getPathMatchConfigurer().getUrlPathHelper();
		if (pathHelper != null) {
			urlProvider.setUrlPathHelper(pathHelper);
		}
		//获取用户在路径匹配配置器配置的PathMatcher
		PathMatcher pathMatcher = getPathMatchConfigurer().getPathMatcher();
		if (pathMatcher != null) {
			urlProvider.setPathMatcher(pathMatcher);
		}
		return urlProvider;
	}

	/**
	 * Return a handler mapping ordered at Integer.MAX_VALUE with a mapped
	 * default servlet handler. To configure "default" Servlet handling,
	 * override {@link #configureDefaultServletHandling}.
	 */
	@Bean
	@Nullable
	public HandlerMapping defaultServletHandlerMapping() {
		Assert.state(this.servletContext != null, "No ServletContext set");
		//默认Servlet处理配置器
		DefaultServletHandlerConfigurer configurer = new DefaultServletHandlerConfigurer(this.servletContext);
		/**
		 * 会以这个默认Servlet处理的配置器为参数调用所有配置类对象中
		 * configureDefaultServletHandling(configurer)方法，用户通过这个配置器启用Tomcat容器
		 * 默认的Servlet，将静态资源请求转发给这个默认的Servlet处理。
		 * 很明显，此处调用configureDefaultServletHandling(configurer)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * configureDefaultServletHandling(configurer)方法，启用Tomcat容器默认的Servlet
		 */
		configureDefaultServletHandling(configurer);
		//应用用户配置构建一个SimpleUrlHandlerMapping
		return configurer.buildHandlerMapping();
	}

	/**
	 * Override this method to configure "default" Servlet handling.
	 * @see DefaultServletHandlerConfigurer
	 */
	protected void configureDefaultServletHandling(DefaultServletHandlerConfigurer configurer) {
	}

	/**
	 * Returns a {@link RequestMappingHandlerAdapter} for processing requests
	 * through annotated controller methods. Consider overriding one of these
	 * other more fine-grained methods:
	 * <ul>
	 * <li>{@link #addArgumentResolvers} for adding custom argument resolvers.
	 * <li>{@link #addReturnValueHandlers} for adding custom return value handlers.
	 * <li>{@link #configureMessageConverters} for adding custom message converters.
	 * </ul>
	 */
	/**
	 * 为RequestMappingHandlerAdapter做了一大堆配置，如下所示
	 *
	 * 获取容器中内容协商管理器并注册进去
	 * 获取所有的消息转换器并注册进去
	 * 获取数据绑定器的初始化器并注册进去
	 * 获取所有用户自定义的参数解析器并注册进去
	 * 获取所有用户自定义的返回值处理器并注册进去
	 * 将用户自定义的异步配置覆盖进去
	 * @param contentNegotiationManager
	 * @param conversionService
	 * @param validator
	 * @return
	 */
	@Bean
	public RequestMappingHandlerAdapter requestMappingHandlerAdapter(
			@Qualifier("mvcContentNegotiationManager") ContentNegotiationManager contentNegotiationManager,
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("mvcValidator") Validator validator) {

		//创建一个RequestMappingHandlerAdapter对象
		RequestMappingHandlerAdapter adapter = createRequestMappingHandlerAdapter();
		adapter.setContentNegotiationManager(contentNegotiationManager);
		/**
		 * getMessageConverters()方法，获取所有的消息转换器
		 * 并将这些消息转换器设置到处理器适配器中
		 */
		adapter.setMessageConverters(getMessageConverters());
		/**
		 * getConfigurableWebBindingInitializer()方法，获取数据绑定器的初始化器
		 * 并将这个初始化器设置到处理器适配器中
		 */
		adapter.setWebBindingInitializer(getConfigurableWebBindingInitializer(conversionService, validator));
		/**
		 * getArgumentResolvers()方法，获取所有用户自定义的参数解析器
		 * 并设置到处理器适配器中
		 */
		adapter.setCustomArgumentResolvers(getArgumentResolvers());
		/**
		 * getReturnValueHandlers()方法，获取所有用户自定义的返回值处理器
		 * 并设置到处理器适配器中
		 */
		adapter.setCustomReturnValueHandlers(getReturnValueHandlers());

		/**
		 * 导入了jackson包，就放入两个通知
		 * 这两个通知分别会在@RequestBody注解参数类型转换前后执行
		 * @ResponseBody注解方法返回值写入响应前后执行
		 */
		if (jackson2Present) {
			adapter.setRequestBodyAdvice(Collections.singletonList(new JsonViewRequestBodyAdvice()));
			adapter.setResponseBodyAdvice(Collections.singletonList(new JsonViewResponseBodyAdvice()));
		}

		/*****************************异步支持的相关配置***********************************/
		//异步支持配置器
		AsyncSupportConfigurer configurer = new AsyncSupportConfigurer();
		/**
		 * 此处调用configureAsyncSupport(configurer)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * configureAsyncSupport(configurer)方法，向这个异步支持配置器中添加用户自定义的配置
		 */
		configureAsyncSupport(configurer);
		if (configurer.getTaskExecutor() != null) {
			//异步执行器，实际上就是线程池，异步完成处理器调用
			adapter.setTaskExecutor(configurer.getTaskExecutor());
		}
		if (configurer.getTimeout() != null) {
			//异步执行的超时时间
			adapter.setAsyncRequestTimeout(configurer.getTimeout());
		}
		//异步支持的拦截器
		adapter.setCallableInterceptors(configurer.getCallableInterceptors());
		adapter.setDeferredResultInterceptors(configurer.getDeferredResultInterceptors());

		return adapter;
	}

	/**
	 * Protected method for plugging in a custom subclass of
	 * {@link RequestMappingHandlerAdapter}.
	 * @since 4.3
	 */
	protected RequestMappingHandlerAdapter createRequestMappingHandlerAdapter() {
		return new RequestMappingHandlerAdapter();
	}

	/**
	 * Returns a {@link HandlerFunctionAdapter} for processing requests through
	 * {@linkplain org.springframework.web.servlet.function.HandlerFunction handler functions}.
	 * @since 5.2
	 */
	@Bean
	public HandlerFunctionAdapter handlerFunctionAdapter() {
		return new HandlerFunctionAdapter();
	}

	/**
	 * Return the {@link ConfigurableWebBindingInitializer} to use for
	 * initializing all {@link WebDataBinder} instances.
	 */
	protected ConfigurableWebBindingInitializer getConfigurableWebBindingInitializer(
			FormattingConversionService mvcConversionService, Validator mvcValidator) {

		//直接new一个数据绑定器的初始化器
		ConfigurableWebBindingInitializer initializer = new ConfigurableWebBindingInitializer();
		//统一转换服务
		initializer.setConversionService(mvcConversionService);
		//校验器
		initializer.setValidator(mvcValidator);
		/**
		 * 此处调用getMessageCodesResolver()方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * getMessageCodesResolver()方法，获取用户配置的消息解码器
		 * 只允许一个配置类重写getMessageCodesResolver()方法返回一个消息解码器
		 */
		MessageCodesResolver messageCodesResolver = getMessageCodesResolver();
		if (messageCodesResolver != null) {
			initializer.setMessageCodesResolver(messageCodesResolver);
		}
		return initializer;
	}

	/**
	 * Override this method to provide a custom {@link MessageCodesResolver}.
	 */
	@Nullable
	protected MessageCodesResolver getMessageCodesResolver() {
		return null;
	}

	/**
	 * Override this method to configure asynchronous request processing options.
	 * @see AsyncSupportConfigurer
	 */
	protected void configureAsyncSupport(AsyncSupportConfigurer configurer) {
	}

	/**
	 * Return a {@link FormattingConversionService} for use with annotated controllers.
	 * <p>See {@link #addFormatters} as an alternative to overriding this method.
	 */
	@Bean
	public FormattingConversionService mvcConversionService() {
		//DefaultFormattingConversionService的构造方法会自动注册一些默认的格式化器，类型转换器
		FormattingConversionService conversionService = new DefaultFormattingConversionService();
		/**
		 * 此处调用addFormatters(conversionService)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * addFormatters(conversionService)方法，向这个格式转换服务中添加用户自定义的配置
		 * 比如注册格式化器，类型转换器等
		 */
		addFormatters(conversionService);
		return conversionService;
	}

	/**
	 * Override this method to add custom {@link Converter} and/or {@link Formatter}
	 * delegates to the common {@link FormattingConversionService}.
	 * @see #mvcConversionService()
	 */
	protected void addFormatters(FormatterRegistry registry) {
	}

	/**
	 * Return a global {@link Validator} instance for example for validating
	 * {@code @ModelAttribute} and {@code @RequestBody} method arguments.
	 * Delegates to {@link #getValidator()} first and if that returns {@code null}
	 * checks the classpath for the presence of a JSR-303 implementations
	 * before creating a {@code OptionalValidatorFactoryBean}.If a JSR-303
	 * implementation is not available, a no-op {@link Validator} is returned.
	 */
	@Bean
	public Validator mvcValidator() {
		/**
		 * 此处调用getValidator()方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * getValidator()方法，获取用户配置的唯一的那一个Validator对象
		 * 比如注册格式化器，类型转换器等
		 */
		Validator validator = getValidator();
		/**
		 * 用户未手动配置Validator，但是
		 * 导入了Validator对应的包，于是自动使用反射实例化一个Validator对象
		 */
		if (validator == null) {
			if (ClassUtils.isPresent("javax.validation.Validator", getClass().getClassLoader())) {
				Class<?> clazz;
				try {
					String className = "org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean";
					clazz = ClassUtils.forName(className, WebMvcConfigurationSupport.class.getClassLoader());
				}
				catch (ClassNotFoundException | LinkageError ex) {
					throw new BeanInitializationException("Failed to resolve default validator class", ex);
				}
				validator = (Validator) BeanUtils.instantiateClass(clazz);
			}
			//不校验的Validator
			else {
				validator = new NoOpValidator();
			}
		}
		return validator;
	}

	/**
	 * Override this method to provide a custom {@link Validator}.
	 */
	@Nullable
	protected Validator getValidator() {
		return null;
	}

	/**
	 * Provide access to the shared custom argument resolvers used by the
	 * {@link RequestMappingHandlerAdapter} and the {@link ExceptionHandlerExceptionResolver}.
	 * <p>This method cannot be overridden; use {@link #addArgumentResolvers} instead.
	 * @since 4.3
	 */
	protected final List<HandlerMethodArgumentResolver> getArgumentResolvers() {
		if (this.argumentResolvers == null) {
			this.argumentResolvers = new ArrayList<>();
			/**
			 * 此处调用addArgumentResolvers(list)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * addArgumentResolvers(list)方法，向这个集合中添加用户自定义的参数解析器
			 */
			addArgumentResolvers(this.argumentResolvers);
		}
		return this.argumentResolvers;
	}

	/**
	 * Add custom {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
	 * to use in addition to the ones registered by default.
	 * <p>Custom argument resolvers are invoked before built-in resolvers except for
	 * those that rely on the presence of annotations (e.g. {@code @RequestParameter},
	 * {@code @PathVariable}, etc). The latter can be customized by configuring the
	 * {@link RequestMappingHandlerAdapter} directly.
	 * @param argumentResolvers the list of custom converters (initially an empty list)
	 */
	protected void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
	}

	/**
	 * Provide access to the shared return value handlers used by the
	 * {@link RequestMappingHandlerAdapter} and the {@link ExceptionHandlerExceptionResolver}.
	 * <p>This method cannot be overridden; use {@link #addReturnValueHandlers} instead.
	 * @since 4.3
	 */
	protected final List<HandlerMethodReturnValueHandler> getReturnValueHandlers() {
		if (this.returnValueHandlers == null) {
			this.returnValueHandlers = new ArrayList<>();
			/**
			 * 此处调用addReturnValueHandlers(list)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * addReturnValueHandlers(list)方法，向这个集合中添加用户自定义的返回值处理器
			 */
			addReturnValueHandlers(this.returnValueHandlers);
		}
		return this.returnValueHandlers;
	}

	/**
	 * Add custom {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}
	 * in addition to the ones registered by default.
	 * <p>Custom return value handlers are invoked before built-in ones except for
	 * those that rely on the presence of annotations (e.g. {@code @ResponseBody},
	 * {@code @ModelAttribute}, etc). The latter can be customized by configuring the
	 * {@link RequestMappingHandlerAdapter} directly.
	 * @param returnValueHandlers the list of custom handlers (initially an empty list)
	 */
	protected void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> returnValueHandlers) {
	}

	/**
	 * Provides access to the shared {@link HttpMessageConverter HttpMessageConverters}
	 * used by the {@link RequestMappingHandlerAdapter} and the
	 * {@link ExceptionHandlerExceptionResolver}.
	 * <p>This method cannot be overridden; use {@link #configureMessageConverters} instead.
	 * Also see {@link #addDefaultHttpMessageConverters} for adding default message converters.
	 */
	//获取所有的消息转换器
	protected final List<HttpMessageConverter<?>> getMessageConverters() {
		if (this.messageConverters == null) {
			this.messageConverters = new ArrayList<>();
			/**
			 * 此处调用configureMessageConverters(list)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * configureMessageConverters(list)方法，向这个集合中添加消息转换器
			 * 通过此方法添加消息转换器，springmvc容器就不会注册默认的消息转换器了
			 */
			configureMessageConverters(this.messageConverters);
			if (this.messageConverters.isEmpty()) {
				//添加默认的消息转换器
				addDefaultHttpMessageConverters(this.messageConverters);
			}
			/**
			 * 此处调用extendMessageConverters(list)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * extendMessageConverters(list)方法，向这个集合中添加消息转换器
			 * 这个方法是在系统默认的转换器基础额外添加用户自定义的消息转换器
			 */
			extendMessageConverters(this.messageConverters);
		}
		return this.messageConverters;
	}

	/**
	 * Override this method to add custom {@link HttpMessageConverter HttpMessageConverters}
	 * to use with the {@link RequestMappingHandlerAdapter} and the
	 * {@link ExceptionHandlerExceptionResolver}.
	 * <p>Adding converters to the list turns off the default converters that would
	 * otherwise be registered by default. Also see {@link #addDefaultHttpMessageConverters}
	 * for adding default message converters.
	 * @param converters a list to add message converters to (initially an empty list)
	 */
	protected void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
	}

	/**
	 * Override this method to extend or modify the list of converters after it has
	 * been configured. This may be useful for example to allow default converters
	 * to be registered and then insert a custom converter through this method.
	 * @param converters the list of configured converters to extend
	 * @since 4.1.3
	 */
	protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
	}

	/**
	 * Adds a set of default HttpMessageConverter instances to the given list.
	 * Subclasses can call this method from {@link #configureMessageConverters}.
	 * @param messageConverters the list to add the default message converters to
	 */
	protected final void addDefaultHttpMessageConverters(List<HttpMessageConverter<?>> messageConverters) {
		messageConverters.add(new ByteArrayHttpMessageConverter());
		messageConverters.add(new StringHttpMessageConverter());
		messageConverters.add(new ResourceHttpMessageConverter());
		messageConverters.add(new ResourceRegionHttpMessageConverter());
		try {
			messageConverters.add(new SourceHttpMessageConverter<>());
		}
		catch (Throwable ex) {
			// Ignore when no TransformerFactory implementation is available...
		}
		messageConverters.add(new AllEncompassingFormHttpMessageConverter());

		if (romePresent) {
			messageConverters.add(new AtomFeedHttpMessageConverter());
			messageConverters.add(new RssChannelHttpMessageConverter());
		}

		if (jackson2XmlPresent) {
			Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.xml();
			if (this.applicationContext != null) {
				builder.applicationContext(this.applicationContext);
			}
			messageConverters.add(new MappingJackson2XmlHttpMessageConverter(builder.build()));
		}
		else if (jaxb2Present) {
			messageConverters.add(new Jaxb2RootElementHttpMessageConverter());
		}

		if (jackson2Present) {
			Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
			if (this.applicationContext != null) {
				builder.applicationContext(this.applicationContext);
			}
			messageConverters.add(new MappingJackson2HttpMessageConverter(builder.build()));
		}
		else if (gsonPresent) {
			messageConverters.add(new GsonHttpMessageConverter());
		}
		else if (jsonbPresent) {
			messageConverters.add(new JsonbHttpMessageConverter());
		}

		if (jackson2SmilePresent) {
			Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.smile();
			if (this.applicationContext != null) {
				builder.applicationContext(this.applicationContext);
			}
			messageConverters.add(new MappingJackson2SmileHttpMessageConverter(builder.build()));
		}
		if (jackson2CborPresent) {
			Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.cbor();
			if (this.applicationContext != null) {
				builder.applicationContext(this.applicationContext);
			}
			messageConverters.add(new MappingJackson2CborHttpMessageConverter(builder.build()));
		}
	}

	/**
	 * Return an instance of {@link CompositeUriComponentsContributor} for use with
	 * {@link org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder}.
	 * @since 4.0
	 */
	@Bean
	public CompositeUriComponentsContributor mvcUriComponentsContributor(
			@Qualifier("mvcConversionService") FormattingConversionService conversionService,
			@Qualifier("requestMappingHandlerAdapter") RequestMappingHandlerAdapter requestMappingHandlerAdapter) {
		return new CompositeUriComponentsContributor(
				requestMappingHandlerAdapter.getArgumentResolvers(), conversionService);
	}

	/**
	 * Returns a {@link HttpRequestHandlerAdapter} for processing requests
	 * with {@link HttpRequestHandler HttpRequestHandlers}.
	 */
	@Bean
	public HttpRequestHandlerAdapter httpRequestHandlerAdapter() {
		return new HttpRequestHandlerAdapter();
	}

	/**
	 * Returns a {@link SimpleControllerHandlerAdapter} for processing requests
	 * with interface-based controllers.
	 */
	@Bean
	public SimpleControllerHandlerAdapter simpleControllerHandlerAdapter() {
		return new SimpleControllerHandlerAdapter();
	}

	/**
	 * Returns a {@link HandlerExceptionResolverComposite} containing a list of exception
	 * resolvers obtained either through {@link #configureHandlerExceptionResolvers} or
	 * through {@link #addDefaultHandlerExceptionResolvers}.
	 * <p><strong>Note:</strong> This method cannot be made final due to CGLIB constraints.
	 * Rather than overriding it, consider overriding {@link #configureHandlerExceptionResolvers}
	 * which allows for providing a list of resolvers.
	 */
	@Bean
	public HandlerExceptionResolver handlerExceptionResolver(
			@Qualifier("mvcContentNegotiationManager") ContentNegotiationManager contentNegotiationManager) {
		//处理器异常解析器
		List<HandlerExceptionResolver> exceptionResolvers = new ArrayList<>();
		/**
		 * 此处调用configureHandlerExceptionResolvers(list)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * configureHandlerExceptionResolvers(list)方法，向这个集合中添加用户自定义的
		 * 处理器异常解析器。注意：通过此方法添加处理器异常解析器，springmvc容器就不会注册默认的
		 * 处理器异常解析器了
		 */
		configureHandlerExceptionResolvers(exceptionResolvers);
		if (exceptionResolvers.isEmpty()) {
			//添加默认的处理器异常解析器
			addDefaultHandlerExceptionResolvers(exceptionResolvers, contentNegotiationManager);
		}
		/**
		 * 此处调用extendHandlerExceptionResolvers(list)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * extendHandlerExceptionResolvers(list)方法，向这个集合中添加处理器异常解析器
		 * 这个方法是在系统默认的处理器异常解析器基础额外添加用户自定义的处理器异常解析器
		 */
		extendHandlerExceptionResolvers(exceptionResolvers);
		//组合模式，多个处理器异常解析器完成解析功能
		HandlerExceptionResolverComposite composite = new HandlerExceptionResolverComposite();
		composite.setOrder(0);
		composite.setExceptionResolvers(exceptionResolvers);
		return composite;
	}

	/**
	 * Override this method to configure the list of
	 * {@link HandlerExceptionResolver HandlerExceptionResolvers} to use.
	 * <p>Adding resolvers to the list turns off the default resolvers that would otherwise
	 * be registered by default. Also see {@link #addDefaultHandlerExceptionResolvers}
	 * that can be used to add the default exception resolvers.
	 * @param exceptionResolvers a list to add exception resolvers to (initially an empty list)
	 */
	protected void configureHandlerExceptionResolvers(List<HandlerExceptionResolver> exceptionResolvers) {
	}

	/**
	 * Override this method to extend or modify the list of
	 * {@link HandlerExceptionResolver HandlerExceptionResolvers} after it has been configured.
	 * <p>This may be useful for example to allow default resolvers to be registered
	 * and then insert a custom one through this method.
	 * @param exceptionResolvers the list of configured resolvers to extend.
	 * @since 4.3
	 */
	protected void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> exceptionResolvers) {
	}

	/**
	 * A method available to subclasses for adding default
	 * {@link HandlerExceptionResolver HandlerExceptionResolvers}.
	 * <p>Adds the following exception resolvers:
	 * <ul>
	 * <li>{@link ExceptionHandlerExceptionResolver} for handling exceptions through
	 * {@link org.springframework.web.bind.annotation.ExceptionHandler} methods.
	 * <li>{@link ResponseStatusExceptionResolver} for exceptions annotated with
	 * {@link org.springframework.web.bind.annotation.ResponseStatus}.
	 * <li>{@link DefaultHandlerExceptionResolver} for resolving known Spring exception types
	 * </ul>
	 */
	/**
	 * ExceptionHandlerExceptionResolver @ExceptionHandler注解方法处理异常
	 * ResponseStatusExceptionResolver @ResponseStatus注解处理异常，响应指定状态
	 * DefaultHandlerExceptionResolver 默认的异常解析器，根据异常类型响应指定的状态码
	 * @param exceptionResolvers
	 * @param mvcContentNegotiationManager
	 */
	protected final void addDefaultHandlerExceptionResolvers(List<HandlerExceptionResolver> exceptionResolvers,
			ContentNegotiationManager mvcContentNegotiationManager) {

		//创建一个使用@ExceptionHandler注解方法处理异常的异常解析器
		ExceptionHandlerExceptionResolver exceptionHandlerResolver = createExceptionHandlerExceptionResolver();
		exceptionHandlerResolver.setContentNegotiationManager(mvcContentNegotiationManager);
		exceptionHandlerResolver.setMessageConverters(getMessageConverters());
		exceptionHandlerResolver.setCustomArgumentResolvers(getArgumentResolvers());
		exceptionHandlerResolver.setCustomReturnValueHandlers(getReturnValueHandlers());
		if (jackson2Present) {
			exceptionHandlerResolver.setResponseBodyAdvice(
					Collections.singletonList(new JsonViewResponseBodyAdvice()));
		}
		if (this.applicationContext != null) {
			exceptionHandlerResolver.setApplicationContext(this.applicationContext);
		}
		exceptionHandlerResolver.afterPropertiesSet();
		exceptionResolvers.add(exceptionHandlerResolver);

		ResponseStatusExceptionResolver responseStatusResolver = new ResponseStatusExceptionResolver();
		responseStatusResolver.setMessageSource(this.applicationContext);
		exceptionResolvers.add(responseStatusResolver);

		exceptionResolvers.add(new DefaultHandlerExceptionResolver());
	}

	/**
	 * Protected method for plugging in a custom subclass of
	 * {@link ExceptionHandlerExceptionResolver}.
	 * @since 4.3
	 */
	protected ExceptionHandlerExceptionResolver createExceptionHandlerExceptionResolver() {
		return new ExceptionHandlerExceptionResolver();
	}

	/**
	 * Register a {@link ViewResolverComposite} that contains a chain of view resolvers
	 * to use for view resolution.
	 * By default this resolver is ordered at 0 unless content negotiation view
	 * resolution is used in which case the order is raised to
	 * {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE
	 * Ordered.HIGHEST_PRECEDENCE}.
	 * <p>If no other resolvers are configured,
	 * {@link ViewResolverComposite#resolveViewName(String, Locale)} returns null in order
	 * to allow other potential {@link ViewResolver} beans to resolve views.
	 * @since 4.1
	 */
	@Bean
	public ViewResolver mvcViewResolver(
			@Qualifier("mvcContentNegotiationManager") ContentNegotiationManager contentNegotiationManager) {
		//视图解析器注册中心
		ViewResolverRegistry registry =
				new ViewResolverRegistry(contentNegotiationManager, this.applicationContext);
		/**
		 * 此处调用configureViewResolvers(registry)方法，
		 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
		 * configureViewResolvers(registry)方法，向这个注册中心中添加用户自定义的
		 * 视图解析器
		 */
		configureViewResolvers(registry);

		//用户未在配置类中注册视图解析器
		if (registry.getViewResolvers().isEmpty() && this.applicationContext != null) {
			//获取容器中所有视图解析器的beanName
			String[] names = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
					this.applicationContext, ViewResolver.class, true, false);
			//只有一个，说明就是当前这个视图解析器mvcViewResolver
			if (names.length == 1) {
				//添加一个默认的视图解析器InternalResourceViewResolver
				registry.getViewResolvers().add(new InternalResourceViewResolver());
			}
		}

		//组合模式，多个视图解析器先后解析一个视图，使用第一个能解析的视图解析器解析
		ViewResolverComposite composite = new ViewResolverComposite();
		composite.setOrder(registry.getOrder());
		composite.setViewResolvers(registry.getViewResolvers());
		if (this.applicationContext != null) {
			composite.setApplicationContext(this.applicationContext);
		}
		if (this.servletContext != null) {
			composite.setServletContext(this.servletContext);
		}
		return composite;
	}

	/**
	 * Override this method to configure view resolution.
	 * @see ViewResolverRegistry
	 */
	protected void configureViewResolvers(ViewResolverRegistry registry) {
	}

	/**
	 * Return the registered {@link CorsConfiguration} objects,
	 * keyed by path pattern.
	 * @since 4.2
	 */
	//获取用户注册的所有跨域配置
	protected final Map<String, CorsConfiguration> getCorsConfigurations() {
		if (this.corsConfigurations == null) {
			//跨域配置注册中心
			CorsRegistry registry = new CorsRegistry();
			/**
			 * 和上面拦截器一样，会以这个跨域配置注册中心为参数调用所有配置类对象中
			 * addCorsMappings(registry)方法，用户通过这个注册中心注册跨域配置。
			 * 很明显，此处调用addCorsMappings(registry)方法，
			 * 就会带着WebMvcConfigurerComposite中所有的配置类对象都调用一次
			 * addCorsMappings(registry)方法，将配置类中配置的跨域配置注册到注册中心
			 */
			addCorsMappings(registry);
			//得到注册中心的所有的跨域配置
			this.corsConfigurations = registry.getCorsConfigurations();
		}
		return this.corsConfigurations;
	}

	/**
	 * Override this method to configure cross origin requests processing.
	 * @since 4.2
	 * @see CorsRegistry
	 */
	protected void addCorsMappings(CorsRegistry registry) {
	}

	@Bean
	@Lazy
	public HandlerMappingIntrospector mvcHandlerMappingIntrospector() {
		return new HandlerMappingIntrospector();
	}


	private static final class NoOpValidator implements Validator {

		@Override
		public boolean supports(Class<?> clazz) {
			return false;
		}

		@Override
		public void validate(@Nullable Object target, Errors errors) {
		}
	}

}
