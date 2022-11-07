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

package org.springframework.context.event;

import java.util.Map;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * {@link GenericApplicationListener} adapter that determines supported event types
 * through introspecting the generically declared type of the target listener.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 3.0
 * @see org.springframework.context.ApplicationListener#onApplicationEvent
 */
//能够将非GenericApplicationListener类型的监听器适配为GenericApplicationListener类型。
public class GenericApplicationListenerAdapter implements GenericApplicationListener, SmartApplicationListener {

	private static final Map<Class<?>, ResolvableType> eventTypeCache = new ConcurrentReferenceHashMap<>();


	//原始的监听器对象
	private final ApplicationListener<ApplicationEvent> delegate;

	//事件类型
	@Nullable
	private final ResolvableType declaredEventType;


	/**
	 * Create a new GenericApplicationListener for the given delegate.
	 * @param delegate the delegate listener to be invoked
	 */
	@SuppressWarnings("unchecked")
	public GenericApplicationListenerAdapter(ApplicationListener<?> delegate) {
		Assert.notNull(delegate, "Delegate listener must not be null");
		this.delegate = (ApplicationListener<ApplicationEvent>) delegate;
		//构造对象时就解析监听器能够监听的事件类型
		this.declaredEventType = resolveDeclaredEventType(this.delegate);
	}


	//监听器接收事件的方法
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		//调用原始的监听器对象的onApplicationEvent方法
		this.delegate.onApplicationEvent(event);
	}

	//监听器是否支持该事件类型
	@Override
	@SuppressWarnings("unchecked")
	public boolean supportsEventType(ResolvableType eventType) {
		//SmartApplicationListener的支持的事件类型判断
		if (this.delegate instanceof SmartApplicationListener) {
			Class<? extends ApplicationEvent> eventClass = (Class<? extends ApplicationEvent>) eventType.resolve();
			return (eventClass != null && ((SmartApplicationListener) this.delegate).supportsEventType(eventClass));
		}
		/**
		 * 1.监听器未指定泛型，那么经过resolveDeclaredEventType方法解析结果就为null，表明
		 * 监听所有类型事件
		 * 2.监听器指定的泛型类型为事件类型的父类或相同
		 * 这两种情况都表明该监听器可以接收该种类型的事件
		 */
		else {
			return (this.declaredEventType == null || this.declaredEventType.isAssignableFrom(eventType));
		}
	}

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return supportsEventType(ResolvableType.forClass(eventType));
	}

	//监听器是否支持该事件源对象类型
	@Override
	public boolean supportsSourceType(@Nullable Class<?> sourceType) {
		/**
		 * 只要我们的监听器不是SmartApplicationListener类型，该方法的返回值总是为true
		 * 并且后面的逻辑判断直接忽略
		 * 如果要使我们的监听器只能处理特定的事件源对象类型的事件，那我们的监听器只需要实现
		 * SmartApplicationListener接口，然后重写supportsSourceType()方法，自定义判断逻辑即可
		 */
		return !(this.delegate instanceof SmartApplicationListener) ||
				((SmartApplicationListener) this.delegate).supportsSourceType(sourceType);
	}

	@Override
	public int getOrder() {
		return (this.delegate instanceof Ordered ? ((Ordered) this.delegate).getOrder() : Ordered.LOWEST_PRECEDENCE);
	}


	@Nullable
	private static ResolvableType resolveDeclaredEventType(ApplicationListener<ApplicationEvent> listener) {
		//获取监听器ApplicationListener接口上的泛型信息
		ResolvableType declaredEventType = resolveDeclaredEventType(listener.getClass());
		//下面是处理创建了代理的监听器
		if (declaredEventType == null || declaredEventType.isAssignableFrom(ApplicationEvent.class)) {
			Class<?> targetClass = AopUtils.getTargetClass(listener);
			if (targetClass != listener.getClass()) {
				declaredEventType = resolveDeclaredEventType(targetClass);
			}
		}
		return declaredEventType;
	}

	@Nullable
	static ResolvableType resolveDeclaredEventType(Class<?> listenerType) {
		//先从缓存中获取
		ResolvableType eventType = eventTypeCache.get(listenerType);
		if (eventType == null) {
			//获取监听器ApplicationListener接口上的泛型信息
			eventType = ResolvableType.forClass(listenerType).as(ApplicationListener.class).getGeneric();
			//解析的结果放入缓存中
			eventTypeCache.put(listenerType, eventType);
		}
		return (eventType != ResolvableType.NONE ? eventType : null);
	}

}
