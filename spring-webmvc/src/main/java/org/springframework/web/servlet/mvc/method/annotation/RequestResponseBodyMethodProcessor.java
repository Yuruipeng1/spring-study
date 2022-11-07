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
import java.lang.reflect.Type;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Resolves method arguments annotated with {@code @RequestBody} and handles return
 * values from methods annotated with {@code @ResponseBody} by reading and writing
 * to the body of the request or response with an {@link HttpMessageConverter}.
 *
 * <p>An {@code @RequestBody} method argument is also validated if it is annotated
 * with {@code @javax.validation.Valid}. In case of validation failure,
 * {@link MethodArgumentNotValidException} is raised and results in an HTTP 400
 * response status code if {@link DefaultHandlerExceptionResolver} is configured.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class RequestResponseBodyMethodProcessor extends AbstractMessageConverterMethodProcessor {

	/**
	 * Basic constructor with converters only. Suitable for resolving
	 * {@code @RequestBody}. For handling {@code @ResponseBody} consider also
	 * providing a {@code ContentNegotiationManager}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters) {
		super(converters);
	}

	/**
	 * Basic constructor with converters and {@code ContentNegotiationManager}.
	 * Suitable for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody} without {@code Request~} or
	 * {@code ResponseBodyAdvice}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager) {

		super(converters, manager);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} method arguments.
	 * For handling {@code @ResponseBody} consider also providing a
	 * {@code ContentNegotiationManager}.
	 * @since 4.2
	 */
	/**
	 * 创建内容协商管理器ContentNegotiationManager
	 * 获取该参数解析器自身支持的媒体类型（从它拥有的消息转换器得到）
	 * 将有@ControllerAdvice注解的标注的RequestBodyAdvice接口和ResponseBodyAdvice接口的实现类封装为RequestResponseBodyAdviceChain执行链
	 * @param converters
	 * @param requestResponseBodyAdvice
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, null, requestResponseBodyAdvice);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, manager, requestResponseBodyAdvice);
	}


	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(RequestBody.class);
	}

	/**
	 * 参数所在类上有没有@ResponseBody注解
	 * 参数所在方法上或返回值上是否标注了@ResponseBody注解
	 * @param returnType
	 * @return
	 */
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return (AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class) ||
				returnType.hasMethodAnnotation(ResponseBody.class));
	}

	/**
	 * Throws MethodArgumentNotValidException if validation fails.
	 * @throws HttpMessageNotReadableException if {@link RequestBody#required()}
	 * is {@code true} and there is no body content or if there is no suitable
	 * converter to read the content with.
	 */
	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {

		//如果参数是Optional容器类型，则返回容器内部参数
		parameter = parameter.nestedIfOptional();
		/**
		 * 读取请求，为参数创建一个参数值
		 * parameter.getNestedGenericParameterType()方法，获取当前方法参数的嵌套泛型类型
		 */
		Object arg = readWithMessageConverters(webRequest, parameter, parameter.getNestedGenericParameterType());
		//获取参数的变量名，类似于user
		String name = Conventions.getVariableNameForParameter(parameter);

		//数据绑定工厂
		if (binderFactory != null) {
			//创建本次请求的数据绑定器
			WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name);
			if (arg != null) {
				//校验数据
				validateIfApplicable(binder, parameter);
				/**
				 * 校验过程中发生错误，统一在此处抛出异常
				 * getBindingResult()方法，
				 */
				if (binder.getBindingResult().hasErrors() && isBindExceptionRequired(binder, parameter)) {
					throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
				}
			}
			if (mavContainer != null) {
				/**
				 * MODEL_KEY_PREFIX=“org.springframework.validation.BindingResult.”
				 * 将绑定结果保存到模型中
				 */
				mavContainer.addAttribute(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
			}
		}

		//适配给定参数值和方法参数
		return adaptArgumentIfNecessary(arg, parameter);
	}

	//读取请求体输入流中的数据，为参数创建一个参数值
	@Override
	protected <T> Object readWithMessageConverters(NativeWebRequest webRequest, MethodParameter parameter,
			Type paramType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		//得到原始的request请求对象
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		Assert.state(servletRequest != null, "No HttpServletRequest");
		//构建一个服务请求对象
		ServletServerHttpRequest inputMessage = new ServletServerHttpRequest(servletRequest);

		//解析请求体内容并进行类型转换
		Object arg = readWithMessageConverters(inputMessage, parameter, paramType);
		if (arg == null && checkRequired(parameter)) {
			throw new HttpMessageNotReadableException("Required request body is missing: " +
					parameter.getExecutable().toGenericString(), inputMessage);
		}
		return arg;
	}

	protected boolean checkRequired(MethodParameter parameter) {
		RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
		return (requestBody != null && requestBody.required() && !parameter.isOptional());
	}

	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		/**
		 * 设置请求已被处理
		 * ModelAndViewContainer中有个requestHandled字段用来标识请求是否被处理
		 */
		mavContainer.setRequestHandled(true);
		//将原始的请求对象包装为ServletServerHttpRequest
		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		//将原始的响应对象包装为ServletServerHttpResponse
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);

		// Try even with null return value. ResponseBodyAdvice could get involved.
		//将返回值写入响应体输出流中
		writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage);
	}

}
