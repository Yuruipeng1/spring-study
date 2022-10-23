/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

/**
 * A base class for resolving method argument values by reading from the body of
 * a request with {@link HttpMessageConverter HttpMessageConverters}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractMessageConverterMethodArgumentResolver implements HandlerMethodArgumentResolver {

	private static final Set<HttpMethod> SUPPORTED_METHODS =
			EnumSet.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

	private static final Object NO_VALUE = new Object();


	protected final Log logger = LogFactory.getLog(getClass());

	//消息转换器
	protected final List<HttpMessageConverter<?>> messageConverters;

	//支持的媒体类型
	protected final List<MediaType> allSupportedMediaTypes;

	//执行链
	private final RequestResponseBodyAdviceChain advice;


	/**
	 * Basic constructor with converters only.
	 */
	public AbstractMessageConverterMethodArgumentResolver(List<HttpMessageConverter<?>> converters) {
		this(converters, null);
	}

	/**
	 * Constructor with converters and {@code Request~} and {@code ResponseBodyAdvice}.
	 * @since 4.2
	 */

	public AbstractMessageConverterMethodArgumentResolver(List<HttpMessageConverter<?>> converters,
			@Nullable List<Object> requestResponseBodyAdvice) {

		Assert.notEmpty(converters, "'messageConverters' must not be empty");
		//消息转换器
		this.messageConverters = converters;
		//这些消息转换器所支持的媒体类型
		this.allSupportedMediaTypes = getAllSupportedMediaTypes(converters);
		//RequestBodyAdvice和ResponseBodyAdvice的执行链
		this.advice = new RequestResponseBodyAdviceChain(requestResponseBodyAdvice);
	}


	/**
	 * Return the media types supported by all provided message converters sorted
	 * by specificity via {@link MediaType#sortBySpecificity(List)}.
	 */
	private static List<MediaType> getAllSupportedMediaTypes(List<HttpMessageConverter<?>> messageConverters) {
		Set<MediaType> allSupportedMediaTypes = new LinkedHashSet<>();
		for (HttpMessageConverter<?> messageConverter : messageConverters) {
			allSupportedMediaTypes.addAll(messageConverter.getSupportedMediaTypes());
		}
		List<MediaType> result = new ArrayList<>(allSupportedMediaTypes);
		MediaType.sortBySpecificity(result);
		return Collections.unmodifiableList(result);
	}


	/**
	 * Return the configured {@link RequestBodyAdvice} and
	 * {@link RequestBodyAdvice} where each instance may be wrapped as a
	 * {@link org.springframework.web.method.ControllerAdviceBean ControllerAdviceBean}.
	 */
	RequestResponseBodyAdviceChain getAdvice() {
		return this.advice;
	}

	/**
	 * Create the method argument value of the expected parameter type by
	 * reading from the given request.
	 * @param <T> the expected type of the argument value to be created
	 * @param webRequest the current request
	 * @param parameter the method parameter descriptor (may be {@code null})
	 * @param paramType the type of the argument value to be created
	 * @return the created method argument value
	 * @throws IOException if the reading from the request fails
	 * @throws HttpMediaTypeNotSupportedException if no suitable message converter is found
	 */
	@Nullable
	protected <T> Object readWithMessageConverters(NativeWebRequest webRequest, MethodParameter parameter,
			Type paramType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		HttpInputMessage inputMessage = createInputMessage(webRequest);
		return readWithMessageConverters(inputMessage, parameter, paramType);
	}

	/**
	 * Create the method argument value of the expected parameter type by reading
	 * from the given HttpInputMessage.
	 * @param <T> the expected type of the argument value to be created
	 * @param inputMessage the HTTP input message representing the current request
	 * @param parameter the method parameter descriptor
	 * @param targetType the target type, not necessarily the same as the method
	 * parameter type, e.g. for {@code HttpEntity<String>}.
	 * @return the created method argument value
	 * @throws IOException if the reading from the request fails
	 * @throws HttpMediaTypeNotSupportedException if no suitable message converter is found
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	protected <T> Object readWithMessageConverters(HttpInputMessage inputMessage, MethodParameter parameter,
			Type targetType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		//媒体类型
		MediaType contentType;
		boolean noContentType = false;
		try {
			/**
			 * 先得到请求的所有请求头
			 * 然后获取内容类型
			 */
			contentType = inputMessage.getHeaders().getContentType();
		}
		catch (InvalidMediaTypeException ex) {
			throw new HttpMediaTypeNotSupportedException(ex.getMessage());
		}
		/**
		 * 未在请求头中指定内容类型
		 * 则按照"application/octet-stream"此种类型解析请求体数据
		 */
		if (contentType == null) {
			noContentType = true;
			contentType = MediaType.APPLICATION_OCTET_STREAM;
		}

		//获取方法参数所在的类，就是@Controller注解类
		Class<?> contextClass = parameter.getContainingClass();
		//参数的clazz对象
		Class<T> targetClass = (targetType instanceof Class ? (Class<T>) targetType : null);
		//重新解析参数clazz对象
		if (targetClass == null) {
			ResolvableType resolvableType = ResolvableType.forMethodParameter(parameter);
			targetClass = (Class<T>) resolvableType.resolve();
		}

		//获取本次请求的请求方式
		HttpMethod httpMethod = (inputMessage instanceof HttpRequest ? ((HttpRequest) inputMessage).getMethod() : null);
		Object body = NO_VALUE;

		EmptyBodyCheckingHttpInputMessage message;
		try {
			//验证请求体输入流中是否包含数据
			message = new EmptyBodyCheckingHttpInputMessage(inputMessage);

			//遍历所有的Http消息转换器，对请求体输入流中的数据进行类型转换
			for (HttpMessageConverter<?> converter : this.messageConverters) {
				//得到当前消息转换器的clazz对象
				Class<HttpMessageConverter<?>> converterType = (Class<HttpMessageConverter<?>>) converter.getClass();
				/**
				 * 在这里把消息转换器分为了两类
				 * 一类是HttpMessageConverter，一类是GenericHttpMessageConverter
				 * 其实它们并没有明显的区别
				 */
				GenericHttpMessageConverter<?> genericConverter =
						(converter instanceof GenericHttpMessageConverter ? (GenericHttpMessageConverter<?>) converter : null);
				//能够转换请求体中内容
				if (genericConverter != null ? genericConverter.canRead(targetType, contextClass, contentType) :
						(targetClass != null && converter.canRead(targetClass, contentType))) {
					//请求体输入流中有数据
					if (message.hasBody()) {
						/**
						 * 消息转换器转换前拦截
						 * 调用所有RequestBodyAdvice对象的beforeBodyRead()方法拦截
						 * getAdvice()方法获取字段advice的值，
						 * 也就是RequestResponseBodyAdviceChain
						 */
						HttpInputMessage msgToUse =
								getAdvice().beforeBodyRead(message, parameter, targetType, converterType);
						//执行消息转换器的read()方法，完成类型转换
						body = (genericConverter != null ? genericConverter.read(targetType, contextClass, msgToUse) :
								((HttpMessageConverter<T>) converter).read(targetClass, msgToUse));
						/**
						 * 消息转换器转换后拦截
						 * 调用所有RequestBodyAdvice对象的afterBodyRead()方法拦截
						 */
						body = getAdvice().afterBodyRead(body, msgToUse, parameter, targetType, converterType);
					}
					/**
					 * 拦截空请求体
					 * 调用所有RequestBodyAdvice对象的handleEmptyBody()方法拦截
					 */
					else {
						body = getAdvice().handleEmptyBody(null, message, parameter, targetType, converterType);
					}
					break;
				}
			}
		}
		catch (IOException ex) {
			throw new HttpMessageNotReadableException("I/O error while reading input message", ex, inputMessage);
		}

		/**
		 * 说明找不到合适的消息转换器进行类型转换,或者请求体中没有数据
		 */
		if (body == NO_VALUE) {
			if (httpMethod == null || !SUPPORTED_METHODS.contains(httpMethod) ||
					(noContentType && !message.hasBody())) {
				return null;
			}
			throw new HttpMediaTypeNotSupportedException(contentType, this.allSupportedMediaTypes);
		}

		MediaType selectedContentType = contentType;
		Object theBody = body;
		LogFormatUtils.traceDebug(logger, traceOn -> {
			String formatted = LogFormatUtils.formatValue(theBody, !traceOn);
			return "Read \"" + selectedContentType + "\" to [" + formatted + "]";
		});

		return body;
	}

	/**
	 * Create a new {@link HttpInputMessage} from the given {@link NativeWebRequest}.
	 * @param webRequest the web request to create an input message from
	 * @return the input message
	 */
	protected ServletServerHttpRequest createInputMessage(NativeWebRequest webRequest) {
		//先拿到原生的请求对象
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		Assert.state(servletRequest != null, "No HttpServletRequest");
		//包装为ServletServerHttpRequest
		return new ServletServerHttpRequest(servletRequest);
	}

	/**
	 * Validate the binding target if applicable.
	 * <p>The default implementation checks for {@code @javax.validation.Valid},
	 * Spring's {@link org.springframework.validation.annotation.Validated},
	 * and custom annotations whose name starts with "Valid".
	 * @param binder the DataBinder to be used
	 * @param parameter the method parameter descriptor
	 * @since 4.1.5
	 * @see #isBindExceptionRequired
	 */
	protected void validateIfApplicable(WebDataBinder binder, MethodParameter parameter) {
		//得到参数上的所有注解信息
		Annotation[] annotations = parameter.getParameterAnnotations();
		//遍历注解
		for (Annotation ann : annotations) {
			//获取@Validated注解信息
			Validated validatedAnn = AnnotationUtils.getAnnotation(ann, Validated.class);
			//如果有Validated注解，或者注解名字以Valid开头，则进入数据校验
			if (validatedAnn != null || ann.annotationType().getSimpleName().startsWith("Valid")) {
				//获取@Validated注解value属性值
				Object hints = (validatedAnn != null ? validatedAnn.value() : AnnotationUtils.getValue(ann));
				Object[] validationHints = (hints instanceof Object[] ? (Object[]) hints : new Object[] {hints});
				/**
				 * 数据校验
				 * 数据绑定器中包含一个数据校验器，springmvc借助它完成校验功能
				 */
				binder.validate(validationHints);
				break;
			}
		}
	}

	/**
	 * Whether to raise a fatal bind exception on validation errors.
	 * @param binder the data binder used to perform data binding
	 * @param parameter the method parameter descriptor
	 * @return {@code true} if the next method argument is not of type {@link Errors}
	 * @since 4.1.5
	 */
	protected boolean isBindExceptionRequired(WebDataBinder binder, MethodParameter parameter) {
		int i = parameter.getParameterIndex();
		Class<?>[] paramTypes = parameter.getExecutable().getParameterTypes();
		boolean hasBindingResult = (paramTypes.length > (i + 1) && Errors.class.isAssignableFrom(paramTypes[i + 1]));
		return !hasBindingResult;
	}

	/**
	 * Adapt the given argument against the method parameter, if necessary.
	 * @param arg the resolved argument
	 * @param parameter the method parameter descriptor
	 * @return the adapted argument, or the original resolved argument as-is
	 * @since 4.3.5
	 */
	@Nullable
	protected Object adaptArgumentIfNecessary(@Nullable Object arg, MethodParameter parameter) {
		/**
		 * 参数真实类型Optional容器
		 * 就执行Optional.of(arg)方法包装为容器
		 */
		if (parameter.getParameterType() == Optional.class) {
			if (arg == null || (arg instanceof Collection && ((Collection<?>) arg).isEmpty()) ||
					(arg instanceof Object[] && ((Object[]) arg).length == 0)) {
				return Optional.empty();
			}
			else {
				return Optional.of(arg);
			}
		}
		return arg;
	}


	private static class EmptyBodyCheckingHttpInputMessage implements HttpInputMessage {

		//请求头
		private final HttpHeaders headers;

		//请求体输入流
		@Nullable
		private final InputStream body;

		/**
		 * 构造对象是就会检查请求体输入流中是否包含数据
		 * 不包含数据，则将body字段置为null
		 */
		public EmptyBodyCheckingHttpInputMessage(HttpInputMessage inputMessage) throws IOException {
			//获取请求头
			this.headers = inputMessage.getHeaders();
			//获取请求体
			InputStream inputStream = inputMessage.getBody();
			if (inputStream.markSupported()) {
				inputStream.mark(1);
				this.body = (inputStream.read() != -1 ? inputStream : null);
				inputStream.reset();
			}
			else {
				/**
				 * 这是一个回退输入流，给了用户第二次读取的机会
				 * 主要验证这个输入流中是否包含数据，
				 * 通过read()方法读取数据之后，可以对数据操作验证之后，再
				 * 通过unread(b)方法将读取数据再放回流中
				 */
				PushbackInputStream pushbackInputStream = new PushbackInputStream(inputStream);
				//读取流中的数据
				int b = pushbackInputStream.read();
				//-1表明流中没有数据，此时不需要流
				if (b == -1) {
					this.body = null;
				}
				//流中有数据，将刚刚读取的数据放回流中
				else {
					this.body = pushbackInputStream;
					pushbackInputStream.unread(b);
				}
			}
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

		@Override
		public InputStream getBody() {
			return (this.body != null ? this.body : StreamUtils.emptyInput());
		}

		public boolean hasBody() {
			return (this.body != null);
		}
	}

}
