/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.context;

import org.springframework.core.ResolvableType;
import org.springframework.core.ResolvableTypeProvider;
import org.springframework.util.Assert;

/**
 * An {@link ApplicationEvent} that carries an arbitrary payload.
 *
 * <p>Mainly intended for internal use within the framework.
 *
 * @author Stephane Nicoll
 * @since 4.2
 * @param <T> the payload type of the event
 */
@SuppressWarnings("serial")
public class PayloadApplicationEvent<T> extends ApplicationEvent implements ResolvableTypeProvider {

	//原事件对象
	private final T payload;


	/**
	 * Create a new PayloadApplicationEvent.
	 * @param source the object on which the event initially occurred (never {@code null})
	 * @param payload the payload object (never {@code null})
	 */
	public PayloadApplicationEvent(Object source, T payload) {
		//保存事件产生位置的类的对象
		super(source);
		Assert.notNull(payload, "Payload must not be null");
		this.payload = payload;
	}


	//使用ResolvableType解析event事件的类型和泛型约束信息
	@Override
	public ResolvableType getResolvableType() {
		/**
		 * getClass()是获取当前类PayloadApplicationEvent的clazz对象
		 * 也就是说不是解析原事件对象的泛型信息
		 */
		return ResolvableType.forClassWithGenerics(getClass(), ResolvableType.forInstance(getPayload()));
	}

	/**
	 * Return the payload of the event.
	 *  //返回原事件对象
	 */
	public T getPayload() {
		return this.payload;
	}

}
