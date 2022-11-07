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

package org.springframework.web.method.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;

/**
 * Extension of {@link HandlerMethod} that invokes the underlying method with
 * argument values resolved from the current HTTP request through a list of
 * {@link HandlerMethodArgumentResolver}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class InvocableHandlerMethod extends HandlerMethod {

	//空参数对象
	private static final Object[] EMPTY_ARGS = new Object[0];


	//处理器方法参数解析器组合
	private HandlerMethodArgumentResolverComposite resolvers = new HandlerMethodArgumentResolverComposite();

	//参数名发现器
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	//数据绑定工厂
	@Nullable
	private WebDataBinderFactory dataBinderFactory;


	/**
	 * Create an instance from a {@code HandlerMethod}.
	 */
	public InvocableHandlerMethod(HandlerMethod handlerMethod) {
		super(handlerMethod);
	}

	/**
	 * Create an instance from a bean instance and a method.
	 */
	public InvocableHandlerMethod(Object bean, Method method) {
		super(bean, method);
	}

	/**
	 * Construct a new handler method with the given bean instance, method name and parameters.
	 * @param bean the object bean
	 * @param methodName the method name
	 * @param parameterTypes the method parameter types
	 * @throws NoSuchMethodException when the method cannot be found
	 */
	public InvocableHandlerMethod(Object bean, String methodName, Class<?>... parameterTypes)
			throws NoSuchMethodException {

		super(bean, methodName, parameterTypes);
	}


	/**
	 * Set {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
	 * to use for resolving method argument values.
	 */
	public void setHandlerMethodArgumentResolvers(HandlerMethodArgumentResolverComposite argumentResolvers) {
		this.resolvers = argumentResolvers;
	}

	/**
	 * Set the ParameterNameDiscoverer for resolving parameter names when needed
	 * (e.g. default request attribute name).
	 * <p>Default is a {@link org.springframework.core.DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Set the {@link WebDataBinderFactory} to be passed to argument resolvers allowing them
	 * to create a {@link WebDataBinder} for data binding and type conversion purposes.
	 */
	public void setDataBinderFactory(WebDataBinderFactory dataBinderFactory) {
		this.dataBinderFactory = dataBinderFactory;
	}


	/**
	 * Invoke the method after resolving its argument values in the context of the given request.
	 * <p>Argument values are commonly resolved through
	 * {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
	 * The {@code providedArgs} parameter however may supply argument values to be used directly,
	 * i.e. without argument resolution. Examples of provided argument values include a
	 * {@link WebDataBinder}, a {@link SessionStatus}, or a thrown exception instance.
	 * Provided argument values are checked before argument resolvers.
	 * <p>Delegates to {@link #getMethodArgumentValues} and calls {@link #doInvoke} with the
	 * resolved arguments.
	 * @param request the current request
	 * @param mavContainer the ModelAndViewContainer for this request
	 * @param providedArgs "given" arguments matched by type, not resolved
	 * @return the raw value returned by the invoked method
	 * @throws Exception raised if no suitable argument resolver can be found,
	 * or if the method raised an exception
	 * @see #getMethodArgumentValues
	 * @see #doInvoke
	 */
	/**
	 * 首先一个一个的解析方法参数，解析时遍历设置的默认的参数解析器，
	 * 找到第一个能解析方法参数的解析器，进行解析方法参数，得到参数值。
	 * 所有参数解析成功后使用反射invoke()方法完成方法调用
	 * @param request
	 * @param mavContainer
	 * @param providedArgs
	 * @return
	 * @throws Exception
	 */
	@Nullable
	public Object invokeForRequest(NativeWebRequest request, @Nullable ModelAndViewContainer mavContainer,
			Object... providedArgs) throws Exception {

		// 取得方法入参值
		Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);
		if (logger.isTraceEnabled()) {
			logger.trace("Arguments: " + Arrays.toString(args));
		}
		// 利用反射执行该方法，并取得返回值
		return doInvoke(args);
	}

	/**
	 * Get the method argument values for the current request, checking the provided
	 * argument values and falling back to the configured argument resolvers.
	 * <p>The resulting array will be passed into {@link #doInvoke}.
	 * @since 5.1.2
	 */
	//获取执行处理器方法的参数对象
	protected Object[] getMethodArgumentValues(NativeWebRequest request, @Nullable ModelAndViewContainer mavContainer,
			Object... providedArgs) throws Exception {

		//获取方法参数
		MethodParameter[] parameters = getMethodParameters();
		//处理器方法没有参数，直接返回一个空数组
		if (ObjectUtils.isEmpty(parameters)) {
			return EMPTY_ARGS;
		}

		//否则开始进入下面参数的解析流程
		Object[] args = new Object[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			//spring定义的类
			MethodParameter parameter = parameters[i];
			//设置方法参数名发现策略
			parameter.initParameterNameDiscovery(this.parameterNameDiscoverer);
			/**
			 * 获取提供的参数
			 * 在这种场景下providedArgs时null，没有指定参数
			 */
			args[i] = findProvidedArgument(parameter, providedArgs);
			if (args[i] != null) {
				continue;
			}
			//参数解析器不支持解析当前参数
			if (!this.resolvers.supportsParameter(parameter)) {
				throw new IllegalStateException(formatArgumentError(parameter, "No suitable resolver"));
			}
			//解析请求映射为处理器方法的参数
			try {
				//解析出参数
				args[i] = this.resolvers.resolveArgument(parameter, mavContainer, request, this.dataBinderFactory);
			}
			catch (Exception ex) {
				// Leave stack trace for later, exception may actually be resolved and handled...
				if (logger.isDebugEnabled()) {
					String exMsg = ex.getMessage();
					if (exMsg != null && !exMsg.contains(parameter.getExecutable().toGenericString())) {
						logger.debug(formatArgumentError(parameter, exMsg));
					}
				}
				throw ex;
			}
		}
		return args;
	}

	/**
	 * Invoke the handler method with the given argument values.
	 */
	@Nullable
	protected Object doInvoke(Object... args) throws Exception {
		// 设置方法的访问权限，BridgedMethod 表示控制器类的指定方法
		ReflectionUtils.makeAccessible(getBridgedMethod());
		try {
			// 关键 -> 利用反射执行该方法，Bean 表示控制器类名称
			return getBridgedMethod().invoke(getBean(), args);
		}
		catch (IllegalArgumentException ex) {
			assertTargetBean(getBridgedMethod(), getBean(), args);
			String text = (ex.getMessage() != null ? ex.getMessage() : "Illegal argument");
			throw new IllegalStateException(formatInvokeError(text, args), ex);
		}
		catch (InvocationTargetException ex) {
			// Unwrap for HandlerExceptionResolvers ...
			Throwable targetException = ex.getTargetException();
			if (targetException instanceof RuntimeException) {
				throw (RuntimeException) targetException;
			}
			else if (targetException instanceof Error) {
				throw (Error) targetException;
			}
			else if (targetException instanceof Exception) {
				throw (Exception) targetException;
			}
			else {
				throw new IllegalStateException(formatInvokeError("Invocation failure", args), targetException);
			}
		}
	}

}
