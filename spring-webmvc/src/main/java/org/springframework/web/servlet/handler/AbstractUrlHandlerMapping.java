/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.web.servlet.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerExecutionChain;

/**
 * Abstract base class for URL-mapped {@link org.springframework.web.servlet.HandlerMapping}
 * implementations. Provides infrastructure for mapping handlers to URLs and configurable
 * URL lookup. For information on the latter, see "alwaysUseFullPath" property.
 *
 * <p>Supports direct matches, e.g. a registered "/test" matches "/test", and
 * various Ant-style pattern matches, e.g. a registered "/t*" pattern matches
 * both "/test" and "/team", "/test/*" matches all paths in the "/test" directory,
 * "/test/**" matches all paths below "/test". For details, see the
 * {@link org.springframework.util.AntPathMatcher AntPathMatcher} javadoc.
 *
 * <p>Will search all path patterns to find the most exact match for the
 * current request path. The most exact match is defined as the longest
 * path pattern that matches the current request path.
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @since 16.04.2003
 */
public abstract class AbstractUrlHandlerMapping extends AbstractHandlerMapping implements MatchableHandlerMapping {

	//默认是null，需要用户指定
	@Nullable
	private Object rootHandler;

	//默认为false，不开启尾部斜杠匹配
	private boolean useTrailingSlashMatch = false;

	private boolean lazyInitHandlers = false;

	private final Map<String, Object> handlerMap = new LinkedHashMap<>();


	/**
	 * Set the root handler for this handler mapping, that is,
	 * the handler to be registered for the root path ("/").
	 * <p>Default is {@code null}, indicating no root handler.
	 */
	public void setRootHandler(@Nullable Object rootHandler) {
		this.rootHandler = rootHandler;
	}

	/**
	 * Return the root handler for this handler mapping (registered for "/"),
	 * or {@code null} if none.
	 */
	@Nullable
	public Object getRootHandler() {
		return this.rootHandler;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * If enabled a URL pattern such as "/users" also matches to "/users/".
	 * <p>The default value is {@code false}.
	 */
	public void setUseTrailingSlashMatch(boolean useTrailingSlashMatch) {
		this.useTrailingSlashMatch = useTrailingSlashMatch;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 */
	public boolean useTrailingSlashMatch() {
		return this.useTrailingSlashMatch;
	}

	/**
	 * Set whether to lazily initialize handlers. Only applicable to
	 * singleton handlers, as prototypes are always lazily initialized.
	 * Default is "false", as eager initialization allows for more efficiency
	 * through referencing the controller objects directly.
	 * <p>If you want to allow your controllers to be lazily initialized,
	 * make them "lazy-init" and set this flag to true. Just making them
	 * "lazy-init" will not work, as they are initialized through the
	 * references from the handler mapping in this case.
	 */
	public void setLazyInitHandlers(boolean lazyInitHandlers) {
		this.lazyInitHandlers = lazyInitHandlers;
	}

	/**
	 * Look up a handler for the URL path of the given request.
	 * @param request current HTTP request
	 * @return the handler instance, or {@code null} if none found
	 */
	@Override
	@Nullable
	protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
		// 根据request获取url
		//获取本次请求路径，去掉项目名之后的路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		/**
		 * 保存到请求域org.springframework.web.servlet.HandlerMapping.lookupPath属性中
		 * 后面需要取出这个路径，然后根据这个路径去匹配拦截器
		 */
		request.setAttribute(LOOKUP_PATH, lookupPath);
		// 根据url查询handler
		Object handler = lookupHandler(lookupPath, request);
		//找不到对应路径的处理器
		if (handler == null) {
			// We need to care for the default handler directly, since we need to
			// expose the PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE for it as well.
			Object rawHandler = null;
			//根路径就使用根路径处理器
			if (StringUtils.matchesCharacter(lookupPath, '/')) {
				//获取根路径的处理器
				rawHandler = getRootHandler();
			}
			//否则使用默认的处理器
			if (rawHandler == null) {
				//获取默认的处理器
				rawHandler = getDefaultHandler();
			}
			//将处理器实例化然后构造为处理器执行器链
			if (rawHandler != null) {
				// Bean name or resolved handler?
				if (rawHandler instanceof String) {
					String handlerName = (String) rawHandler;
					rawHandler = obtainApplicationContext().getBean(handlerName);
				}
				// 预留的校验handler模板方法,没有使用
				validateHandler(rawHandler, request);
				// 添加expose属性到request的拦截器
				handler = buildPathExposingHandler(rawHandler, lookupPath, lookupPath, null);
			}
		}
		return handler;
	}

	/**
	 * Look up a handler instance for the given URL path.
	 * <p>Supports direct matches, e.g. a registered "/test" matches "/test",
	 * and various Ant-style pattern matches, e.g. a registered "/t*" matches
	 * both "/test" and "/team". For details, see the AntPathMatcher class.
	 * <p>Looks for the most exact pattern, where most exact is defined as
	 * the longest path pattern.
	 * @param urlPath the URL the bean is mapped to
	 * @param request current HTTP request (to expose the path within the mapping to)
	 * @return the associated handler instance, or {@code null} if not found
	 * @see #exposePathWithinMapping
	 * @see org.springframework.util.AntPathMatcher
	 */
	@Nullable
	protected Object lookupHandler(String urlPath, HttpServletRequest request) throws Exception {
		// Direct match? 直接根据url进行查找handler
		Object handler = this.handlerMap.get(urlPath);
		if (handler != null) {
			// Bean name or resolved handler?
			//处理器还未实例化，先实例化
			if (handler instanceof String) {
				String handlerName = (String) handler;
				handler = obtainApplicationContext().getBean(handlerName);
			}
			//校验处理器，空方法，直接忽略
			validateHandler(handler, request);
			//构建一个处理器执行器对象
			return buildPathExposingHandler(handler, urlPath, urlPath, null);
		}

		// Pattern match?
		//不能直接匹配
		List<String> matchingPatterns = new ArrayList<>();
		for (String registeredPattern : this.handlerMap.keySet()) {
			/**
			 * getPathMatcher()方法，获取路径匹配器
			 * match(registeredPattern, urlPath)方法，匹配两个路径
			 */
			if (getPathMatcher().match(registeredPattern, urlPath)) {
				matchingPatterns.add(registeredPattern);
			}
			//不匹配，就判断是否开启尾部斜杠匹配，然后进行尾部斜杆匹配
			else if (useTrailingSlashMatch()) {
				if (!registeredPattern.endsWith("/") && getPathMatcher().match(registeredPattern + "/", urlPath)) {
					matchingPatterns.add(registeredPattern + "/");
				}
			}
		}

		String bestMatch = null;
		//匹配规则,路径比较器
		Comparator<String> patternComparator = getPathMatcher().getPatternComparator(urlPath);
		//使用路径比较器对多个匹配的路径进行排序，然后使用第一个匹配的路径
		if (!matchingPatterns.isEmpty()) {
			matchingPatterns.sort(patternComparator);
			if (logger.isTraceEnabled() && matchingPatterns.size() > 1) {
				logger.trace("Matching patterns " + matchingPatterns);
			}
			bestMatch = matchingPatterns.get(0);
		}
		if (bestMatch != null) {
			//获取第一个匹配路径对应的ResourceHttpRequestHandler
			handler = this.handlerMap.get(bestMatch);
			//以斜杠结束，就去掉斜杠，再匹配
			if (handler == null) {
				if (bestMatch.endsWith("/")) {
					handler = this.handlerMap.get(bestMatch.substring(0, bestMatch.length() - 1));
				}
				if (handler == null) {
					throw new IllegalStateException(
							"Could not find handler for best pattern match [" + bestMatch + "]");
				}
			}
			// Bean name or resolved handler?
			//从容器中获取对应名字的处理器对象
			if (handler instanceof String) {
				String handlerName = (String) handler;
				handler = obtainApplicationContext().getBean(handlerName);
			}
			//校验处理器，空方法，直接忽略
			validateHandler(handler, request);
			/**
			 * 截取掉请求路径中匹配的路径
			 * 例如js/**和js/test.js，就会得到test.js
			 */
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(bestMatch, urlPath);

			// There might be multiple 'best patterns', let's make sure we have the correct URI template variables
			// for all of them
			//模板变量
			Map<String, String> uriTemplateVariables = new LinkedHashMap<>();
			for (String matchingPattern : matchingPatterns) {
				if (patternComparator.compare(bestMatch, matchingPattern) == 0) {
					//得到模板变量的映射关系
					Map<String, String> vars = getPathMatcher().extractUriTemplateVariables(matchingPattern, urlPath);
					//解码
					Map<String, String> decodedVars = getUrlPathHelper().decodePathVariables(request, vars);
					//放入模板变量集合中
					uriTemplateVariables.putAll(decodedVars);
				}
			}
			if (logger.isTraceEnabled() && uriTemplateVariables.size() > 0) {
				logger.trace("URI variables " + uriTemplateVariables);
			}
			//构建一个处理器执行器对象
			return buildPathExposingHandler(handler, bestMatch, pathWithinMapping, uriTemplateVariables);
		}

		// No handler found...
		return null;
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param handler the handler object to validate
	 * @param request current HTTP request
	 * @throws Exception if validation failed
	 */
	protected void validateHandler(Object handler, HttpServletRequest request) throws Exception {
	}

	/**
	 * Build a handler object for the given raw handler, exposing the actual
	 * handler, the {@link #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}, as well as
	 * the {@link #URI_TEMPLATE_VARIABLES_ATTRIBUTE} before executing the handler.
	 * <p>The default implementation builds a {@link HandlerExecutionChain}
	 * with a special interceptor that exposes the path attribute and uri template variables
	 * @param rawHandler the raw handler to expose
	 * @param pathWithinMapping the path to expose before executing the handler
	 * @param uriTemplateVariables the URI template variables, can be {@code null} if no variables found
	 * @return the final handler object
	 */

	//用于给找到的Handler注册两个拦截器PathExposingHandlerInterceptor和UriTemplateVariablesHandlerInterceptor
	//这两个拦截器的的作用是将与当前url实际匹配的Pattern、匹配条件和url模板参数设置到request的属性中
	protected Object buildPathExposingHandler(Object rawHandler, String bestMatchingPattern,
			String pathWithinMapping, @Nullable Map<String, String> uriTemplateVariables) {

		//构建一个处理器执行器链
		HandlerExecutionChain chain = new HandlerExecutionChain(rawHandler);
		//添加一个默认的拦截器，这个拦截器用来将解析好的请求路径保存到请求域中
		chain.addInterceptor(new PathExposingHandlerInterceptor(bestMatchingPattern, pathWithinMapping));
		//添加一个默认的拦截器，用来处理模板变量
		if (!CollectionUtils.isEmpty(uriTemplateVariables)) {
			chain.addInterceptor(new UriTemplateVariablesHandlerInterceptor(uriTemplateVariables));
		}
		return chain;
	}

	/**
	 * Expose the path within the current mapping as request attribute.
	 * @param pathWithinMapping the path within the current mapping
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposePathWithinMapping(String bestMatchingPattern, String pathWithinMapping,
			HttpServletRequest request) {

		//最匹配的资源路径
		request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestMatchingPattern);
		request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, pathWithinMapping);
	}

	/**
	 * Expose the URI templates variables as request attribute.
	 * @param uriTemplateVariables the URI template variables
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposeUriTemplateVariables(Map<String, String> uriTemplateVariables, HttpServletRequest request) {
		request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriTemplateVariables);
	}

	@Override
	@Nullable
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request, LOOKUP_PATH);
		if (getPathMatcher().match(pattern, lookupPath)) {
			return new RequestMatchResult(pattern, lookupPath, getPathMatcher());
		}
		else if (useTrailingSlashMatch()) {
			if (!pattern.endsWith("/") && getPathMatcher().match(pattern + "/", lookupPath)) {
				return new RequestMatchResult(pattern + "/", lookupPath, getPathMatcher());
			}
		}
		return null;
	}

	/**
	 * Register the specified handler for the given URL paths.
	 * @param urlPaths the URLs that the bean should be mapped to
	 * @param beanName the name of the handler bean
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	//注册url和Bean的map，注册多个string的url到一个处理器中
	protected void registerHandler(String[] urlPaths, String beanName) throws BeansException, IllegalStateException {
		Assert.notNull(urlPaths, "URL path array must not be null");
		for (String urlPath : urlPaths) {
			registerHandler(urlPath, beanName);
		}
	}

	/**
	 * Register the specified handler for the given URL path.
	 * @param urlPath the URL the bean should be mapped to
	 * @param handler the handler instance or handler bean name String
	 * (a bean name will automatically be resolved into the corresponding handler bean)
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String urlPath, Object handler) throws BeansException, IllegalStateException {
		Assert.notNull(urlPath, "URL path must not be null");
		Assert.notNull(handler, "Handler object must not be null");
		Object resolvedHandler = handler;

		// Eagerly resolve handler if referencing singleton via name.
		//如果Handler是String类型而且没有设置lazyInitHandlers则从springMVC容器中获取handler
		if (!this.lazyInitHandlers && handler instanceof String) {
			String handlerName = (String) handler;
			ApplicationContext applicationContext = obtainApplicationContext();
			if (applicationContext.isSingleton(handlerName)) {
				resolvedHandler = applicationContext.getBean(handlerName);
			}
		}
		//是否已存在对应的handler
		Object mappedHandler = this.handlerMap.get(urlPath);
		if (mappedHandler != null) {
			if (mappedHandler != resolvedHandler) {
				throw new IllegalStateException(
						"Cannot map " + getHandlerDescription(handler) + " to URL path [" + urlPath +
						"]: There is already " + getHandlerDescription(mappedHandler) + " mapped.");
			}
		}
		else {
			if (urlPath.equals("/")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Root mapping to " + getHandlerDescription(handler));
				}
				//"/"-->设置为roothandler
				setRootHandler(resolvedHandler);
			}
			else if (urlPath.equals("/*")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Default mapping to " + getHandlerDescription(handler));
				}
				//对"/*"的匹配设置默认的handler
				setDefaultHandler(resolvedHandler);
			}
			else {
				//其余的路径绑定关系则存入handlerMap
				this.handlerMap.put(urlPath, resolvedHandler);
				if (logger.isTraceEnabled()) {
					logger.trace("Mapped [" + urlPath + "] onto " + getHandlerDescription(handler));
				}
			}
		}
	}

	private String getHandlerDescription(Object handler) {
		return (handler instanceof String ? "'" + handler + "'" : handler.toString());
	}


	/**
	 * Return the registered handlers as an unmodifiable Map, with the registered path
	 * as key and the handler object (or handler bean name in case of a lazy-init handler)
	 * as value.
	 * @see #getDefaultHandler()
	 */
	public final Map<String, Object> getHandlerMap() {
		return Collections.unmodifiableMap(this.handlerMap);
	}

	/**
	 * Indicates whether this handler mapping support type-level mappings. Default to {@code false}.
	 */
	protected boolean supportsTypeLevelMappings() {
		//默认是不支持类级别映射的
		return false;
	}


	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class PathExposingHandlerInterceptor extends HandlerInterceptorAdapter {

		/**
		 * 对于静态资源请求来说，它就是资源匹配的路径
		 * 例如<mvc:resources mapping="/js/**">
		 * <mvc:resources mapping="/css/**">
		 * 如果请求是以js开头的，那么它最匹配的路径就是"/js/**"，也就是该字段的值
		 */
		private final String bestMatchingPattern;

		/**
		 * 对于静态资源请求来说，它就是截取掉请求路径中匹配的路径之后，剩下的路径
		 * 比如js/**和js/test.js，那么它就是test.js
		 */
		private final String pathWithinMapping;

		public PathExposingHandlerInterceptor(String bestMatchingPattern, String pathWithinMapping) {
			this.bestMatchingPattern = bestMatchingPattern;
			this.pathWithinMapping = pathWithinMapping;
		}

		//处理器执行之前调用
		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			//将解析好的路径保存到请求域中
			exposePathWithinMapping(this.bestMatchingPattern, this.pathWithinMapping, request);
			//将处理器对象也保存到请求域中
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
			//将是否支持类级别映射的结果也保存到请求域中
			request.setAttribute(INTROSPECT_TYPE_LEVEL_MAPPING, supportsTypeLevelMappings());
			return true;
		}

	}

	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class UriTemplateVariablesHandlerInterceptor extends HandlerInterceptorAdapter {

		private final Map<String, String> uriTemplateVariables;

		public UriTemplateVariablesHandlerInterceptor(Map<String, String> uriTemplateVariables) {
			this.uriTemplateVariables = uriTemplateVariables;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			exposeUriTemplateVariables(this.uriTemplateVariables, request);
			return true;
		}
	}

}
