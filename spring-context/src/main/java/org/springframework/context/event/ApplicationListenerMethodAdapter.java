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

package org.springframework.context.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * {@link GenericApplicationListener} adapter that delegates the processing of
 * an event to an {@link EventListener} annotated method.
 *
 * <p>Delegates to {@link #processEvent(ApplicationEvent)} to give subclasses
 * a chance to deviate from the default. Unwraps the content of a
 * {@link PayloadApplicationEvent} if necessary to allow a method declaration
 * to define any arbitrary event type. If a condition is defined, it is
 * evaluated prior to invoking the underlying method.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.2
 */
public class ApplicationListenerMethodAdapter implements GenericApplicationListener {

	private static final boolean reactiveStreamsPresent = ClassUtils.isPresent(
			"org.reactivestreams.Publisher", ApplicationListenerMethodAdapter.class.getClassLoader());


	protected final Log logger = LogFactory.getLog(getClass());

	//监听方法所属的bean的名字
	private final String beanName;

	//监听方法的桥接方法对象
	private final Method method;

	//监听方法原对象
	private final Method targetMethod;

	private final AnnotatedElementKey methodKey;

	//能够监听的事件的类型
	private final List<ResolvableType> declaredEventTypes;

	//监听的条件（使用SpEL表达式）
	@Nullable
	private final String condition;

	//监听器的顺序
	private final int order;

	@Nullable
	private ApplicationContext applicationContext;

	@Nullable
	private EventExpressionEvaluator evaluator;


	public ApplicationListenerMethodAdapter(String beanName, Class<?> targetClass, Method method) {
		//监听方法所属的bean的名字
		this.beanName = beanName;
		//当前方法的桥接方法对象
		this.method = BridgeMethodResolver.findBridgedMethod(method);
		/**
		 * 如果是非代理类，那么就获取method在targetClass类上重写的方法对象
		 * 如果是代理类，那就直接使用当前桥接方法对象
		 * getMostSpecificMethod方法，它会获取method在指定bean上的方法对象
		 */
		this.targetMethod = (!Proxy.isProxyClass(targetClass) ?
				AopUtils.getMostSpecificMethod(method, targetClass) : this.method);
		this.methodKey = new AnnotatedElementKey(this.targetMethod, targetClass);

		//获取目标方法上的@EventListener注解信息
		EventListener ann = AnnotatedElementUtils.findMergedAnnotation(this.targetMethod, EventListener.class);
		//解析该注解上声明的监听的事件类型
		this.declaredEventTypes = resolveDeclaredEventTypes(method, ann);
		//获取@EventListener注解的condition属性
		this.condition = (ann != null ? ann.condition() : null);
		//解析监听方法上标注的@Order注解
		this.order = resolveOrder(this.targetMethod);
	}

	/********************解析@EventListener注解上声明的监听事件的类型**********************/
	private static List<ResolvableType> resolveDeclaredEventTypes(Method method, @Nullable EventListener ann) {
		//监听方法的参数只能为一个
		int count = method.getParameterCount();
		if (count > 1) {
			throw new IllegalStateException(
					"Maximum one parameter is allowed for event listener method: " + method);
		}

		if (ann != null) {
			//获取用户在@EventListener注解的classes属性中指定的clazz对象
			Class<?>[] classes = ann.classes();
			if (classes.length > 0) {
				List<ResolvableType> types = new ArrayList<>(classes.length);
				for (Class<?> eventType : classes) {
					//ResolvableType.forClass()得到对应类的ResolvableType类型
					types.add(ResolvableType.forClass(eventType));
				}
				return types;
			}
		}

		if (count == 0) {
			throw new IllegalStateException(
					"Event parameter is mandatory for event listener method: " + method);
		}
		/**
		 * ResolvableType.forMethodParameter(method, 0)
		 * 获取方法索引为0的参数的ResolvableType类型
		 */
		return Collections.singletonList(ResolvableType.forMethodParameter(method, 0));
	}

	/****************************解析监听方法上标注的@Order注解***************************/
	private static int resolveOrder(Method method) {
		Order ann = AnnotatedElementUtils.findMergedAnnotation(method, Order.class);
		return (ann != null ? ann.value() : 0);
	}


	/**
	 * Initialize this instance.
	 */
	void init(ApplicationContext applicationContext, EventExpressionEvaluator evaluator) {
		this.applicationContext = applicationContext;
		this.evaluator = evaluator;
	}


	//（处理监听到的事件的方法）
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		processEvent(event);
	}

	//（是否支持处理当前事件）
	@Override
	public boolean supportsEventType(ResolvableType eventType) {
		for (ResolvableType declaredEventType : this.declaredEventTypes) {
			//@EventListener注解上声明事件类型是当前事件的父类或相同，则支持处理该事件
			if (declaredEventType.isAssignableFrom(eventType)) {
				return true;
			}
			/**
			 * PayloadApplicationEvent是适配事件，当用户自定义的事件为实现ApplicationEvent接口时
			 * spring在发布事件时会自动将该事件适配为PayloadApplicationEvent
			 */
			if (PayloadApplicationEvent.class.isAssignableFrom(eventType.toClass())) {
				//获取适配事件的泛型信息，实际就是用户自定义事件的类型
				ResolvableType payloadType = eventType.as(PayloadApplicationEvent.class).getGeneric();
				if (declaredEventType.isAssignableFrom(payloadType)) {
					return true;
				}
			}
		}
		//判断当前事件对象中是否包含不可解析的泛型
		return eventType.hasUnresolvableGenerics();
	}

	//是否支持处理当前事件源对象
	@Override
	public boolean supportsSourceType(@Nullable Class<?> sourceType) {
		return true;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	/**
	 * Process the specified {@link ApplicationEvent}, checking if the condition
	 * matches and handling a non-null result, if any.
	 */
	public void processEvent(ApplicationEvent event) {
		//解析得到监听方法的参数值，方便通过反射直接调用方法监听方法
		Object[] args = resolveArguments(event);
		//监听方法允许处理事件
		if (shouldHandle(event, args)) {
			//反射执行监听方法
			Object result = doInvoke(args);
			if (result != null) {
				handleResult(result);
			}
			else {
				logger.trace("No result object given - no result to handle");
			}
		}
	}

	/**
	 * Resolve the method arguments to use for the specified {@link ApplicationEvent}.
	 * <p>These arguments will be used to invoke the method handled by this instance.
	 * Can return {@code null} to indicate that no suitable arguments could be resolved
	 * and therefore the method should not be invoked at all for the specified event.
	 */
	//解析得到监听方法的参数值
	@Nullable
	protected Object[] resolveArguments(ApplicationEvent event) {
		//获取该事件在@EventListener注解中声明支持处理的ResolvableType类型
		ResolvableType declaredEventType = getResolvableType(event);
		//不支持处理该事件
		if (declaredEventType == null) {
			return null;
		}
		//监听方法的参数个数为0，不需要参数
		if (this.method.getParameterCount() == 0) {
			return new Object[0];
		}
		//事件的clazz对象
		Class<?> declaredEventClass = declaredEventType.toClass();
		//用户自定义的未实现ApplicationEvent接口的事件
		if (!ApplicationEvent.class.isAssignableFrom(declaredEventClass) &&
				event instanceof PayloadApplicationEvent) {
			//获取原始的事件对象
			Object payload = ((PayloadApplicationEvent<?>) event).getPayload();
			if (declaredEventClass.isInstance(payload)) {
				return new Object[] {payload};
			}
		}
		//实现了ApplicationEvent接口的事件
		return new Object[] {event};
	}

	//处理监听方法执行之后的结果
	protected void handleResult(Object result) {
		if (reactiveStreamsPresent && new ReactiveResultHandler().subscribeToPublisher(result)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Adapted to reactive result: " + result);
			}
		}
		else if (result instanceof CompletionStage) {
			((CompletionStage<?>) result).whenComplete((event, ex) -> {
				if (ex != null) {
					handleAsyncError(ex);
				}
				else if (event != null) {
					publishEvent(event);
				}
			});
		}
		else if (result instanceof ListenableFuture) {
			((ListenableFuture<?>) result).addCallback(this::publishEvents, this::handleAsyncError);
		}
		else {
			publishEvents(result);
		}
	}

	private void publishEvents(Object result) {
		if (result.getClass().isArray()) {
			Object[] events = ObjectUtils.toObjectArray(result);
			for (Object event : events) {
				publishEvent(event);
			}
		}
		else if (result instanceof Collection<?>) {
			Collection<?> events = (Collection<?>) result;
			for (Object event : events) {
				publishEvent(event);
			}
		}
		else {
			publishEvent(result);
		}
	}

	private void publishEvent(@Nullable Object event) {
		if (event != null) {
			Assert.notNull(this.applicationContext, "ApplicationContext must not be null");
			this.applicationContext.publishEvent(event);
		}
	}

	protected void handleAsyncError(Throwable t) {
		logger.error("Unexpected error occurred in asynchronous listener", t);
	}

	//判断监听方法是否允许处理事件
	private boolean shouldHandle(ApplicationEvent event, @Nullable Object[] args) {
		if (args == null) {
			return false;
		}
		//获取@EventListener注解上指定的SpEL表达式
		String condition = getCondition();
		//用户指定了SpEL表达式
		if (StringUtils.hasText(condition)) {
			Assert.notNull(this.evaluator, "EventExpressionEvaluator must not be null");
			//计算SpEL表达式的结果
			return this.evaluator.condition(
					condition, event, this.targetMethod, this.methodKey, args, this.applicationContext);
		}
		//用户未指定SpEL表达式，允许处理事件
		return true;
	}

	/**
	 * Invoke the event listener method with the given argument values.
	 */
	@Nullable
	//反射执行监听方法
	protected Object doInvoke(Object... args) {
		//从容器中获取监听方法所在类的对象
		Object bean = getTargetBean();
		// Detect package-protected NullBean instance through equals(null) check
		if (bean.equals(null)) {
			return null;
		}

		//允许监听方法强制执行
		ReflectionUtils.makeAccessible(this.method);
		try {
			//反射执行监听方法
			return this.method.invoke(bean, args);
		}
		catch (IllegalArgumentException ex) {
			assertTargetBean(this.method, bean, args);
			throw new IllegalStateException(getInvocationErrorMessage(bean, ex.getMessage(), args), ex);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException(getInvocationErrorMessage(bean, ex.getMessage(), args), ex);
		}
		catch (InvocationTargetException ex) {
			// Throw underlying exception
			Throwable targetException = ex.getTargetException();
			if (targetException instanceof RuntimeException) {
				throw (RuntimeException) targetException;
			}
			else {
				String msg = getInvocationErrorMessage(bean, "Failed to invoke event listener method", args);
				throw new UndeclaredThrowableException(targetException, msg);
			}
		}
	}

	/**
	 * Return the target bean instance to use.
	 */
	protected Object getTargetBean() {
		Assert.notNull(this.applicationContext, "ApplicationContext must no be null");
		//从容器中获取beanName的对象
		return this.applicationContext.getBean(this.beanName);
	}

	/**
	 * Return the condition to use.
	 * <p>Matches the {@code condition} attribute of the {@link EventListener}
	 * annotation or any matching attribute on a composed annotation that
	 * is meta-annotated with {@code @EventListener}.
	 */
	@Nullable
	protected String getCondition() {
		/**
		 * 构造ApplicationListenerMethodAdapter对象的时候，就解析@EventListener注解
		 * 得到了condition属性信息
		 */
		return this.condition;
	}

	/**
	 * Add additional details such as the bean type and method signature to
	 * the given error message.
	 * @param message error message to append the HandlerMethod details to
	 */
	protected String getDetailedErrorMessage(Object bean, String message) {
		StringBuilder sb = new StringBuilder(message).append("\n");
		sb.append("HandlerMethod details: \n");
		sb.append("Bean [").append(bean.getClass().getName()).append("]\n");
		sb.append("Method [").append(this.method.toGenericString()).append("]\n");
		return sb.toString();
	}

	/**
	 * Assert that the target bean class is an instance of the class where the given
	 * method is declared. In some cases the actual bean instance at event-
	 * processing time may be a JDK dynamic proxy (lazy initialization, prototype
	 * beans, and others). Event listener beans that require proxying should prefer
	 * class-based proxy mechanisms.
	 */
	private void assertTargetBean(Method method, Object targetBean, Object[] args) {
		Class<?> methodDeclaringClass = method.getDeclaringClass();
		Class<?> targetBeanClass = targetBean.getClass();
		if (!methodDeclaringClass.isAssignableFrom(targetBeanClass)) {
			String msg = "The event listener method class '" + methodDeclaringClass.getName() +
					"' is not an instance of the actual bean class '" +
					targetBeanClass.getName() + "'. If the bean requires proxying " +
					"(e.g. due to @Transactional), please use class-based proxying.";
			throw new IllegalStateException(getInvocationErrorMessage(targetBean, msg, args));
		}
	}

	private String getInvocationErrorMessage(Object bean, String message, Object[] resolvedArgs) {
		StringBuilder sb = new StringBuilder(getDetailedErrorMessage(bean, message));
		sb.append("Resolved arguments: \n");
		for (int i = 0; i < resolvedArgs.length; i++) {
			sb.append("[").append(i).append("] ");
			if (resolvedArgs[i] == null) {
				sb.append("[null] \n");
			}
			else {
				sb.append("[type=").append(resolvedArgs[i].getClass().getName()).append("] ");
				sb.append("[value=").append(resolvedArgs[i]).append("]\n");
			}
		}
		return sb.toString();
	}

	/*********************************解析事件类型*************************************/
	@Nullable
	private ResolvableType getResolvableType(ApplicationEvent event) {
		ResolvableType payloadType = null;
		//当前事件是用户自定义的未实现ApplicationEvent接口的事件
		if (event instanceof PayloadApplicationEvent) {
			PayloadApplicationEvent<?> payloadEvent = (PayloadApplicationEvent<?>) event;
			ResolvableType eventType = payloadEvent.getResolvableType();
			if (eventType != null) {
				//获取用户自定义事件的真实类型
				payloadType = eventType.as(PayloadApplicationEvent.class).getGeneric();
			}
		}
		//遍历用户在@EventListener注解上声明的可监听的事件类型
		for (ResolvableType declaredEventType : this.declaredEventTypes) {
			//获取可监听的事件的clazz对象
			Class<?> eventClass = declaredEventType.toClass();
			/**
			 * 如果声明的可监听的事件类型中包含用户自定义的事件类型
			 * 那么就返回声明的类型
			 */
			if (!ApplicationEvent.class.isAssignableFrom(eventClass) &&
					payloadType != null && declaredEventType.isAssignableFrom(payloadType)) {
				return declaredEventType;
			}
			/**
			 * 如果当前事件对象是声明的可监听的事件类型的一个实例
			 * 那么就返回声明的类型
			 */
			if (eventClass.isInstance(event)) {
				return declaredEventType;
			}
		}
		return null;
	}


	@Override
	public String toString() {
		return this.method.toGenericString();
	}


	private class ReactiveResultHandler {

		public boolean subscribeToPublisher(Object result) {
			ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance().getAdapter(result.getClass());
			if (adapter != null) {
				adapter.toPublisher(result).subscribe(new EventPublicationSubscriber());
				return true;
			}
			return false;
		}
	}


	private class EventPublicationSubscriber implements Subscriber<Object> {

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Integer.MAX_VALUE);
		}

		@Override
		public void onNext(Object o) {
			publishEvents(o);
		}

		@Override
		public void onError(Throwable t) {
			handleAsyncError(t);
		}

		@Override
		public void onComplete() {
		}
	}

}