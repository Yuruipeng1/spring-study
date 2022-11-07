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

package org.springframework.web.method.annotation;

import java.util.Collections;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.support.DefaultDataBinderFactory;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;

/**
 * Adds initialization to a WebDataBinder via {@code @InitBinder} methods.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public class InitBinderDataBinderFactory extends DefaultDataBinderFactory {

	//保存有@InitBinder注解的方法
	private final List<InvocableHandlerMethod> binderMethods;


	/**
	 * Create a new InitBinderDataBinderFactory instance.
	 * @param binderMethods {@code @InitBinder} methods
	 * @param initializer for global data binder initialization
	 */
	public InitBinderDataBinderFactory(@Nullable List<InvocableHandlerMethod> binderMethods,
			@Nullable WebBindingInitializer initializer) {

		super(initializer);
		this.binderMethods = (binderMethods != null ? binderMethods : Collections.emptyList());
	}


	/**
	 * Initialize a WebDataBinder with {@code @InitBinder} methods.
	 * <p>If the {@code @InitBinder} annotation specifies attributes names,
	 * it is invoked only if the names include the target object name.
	 * @throws Exception if one of the invoked @{@link InitBinder} methods fails
	 * @see #isBinderMethodApplicable
	 */
	//初始化数据绑定器
	@Override
	public void initBinder(WebDataBinder dataBinder, NativeWebRequest request) throws Exception {
		//遍历所有的@InitBinder注解的方法
		for (InvocableHandlerMethod binderMethod : this.binderMethods) {
			//过滤掉不符合条件的方法
			if (isBinderMethodApplicable(binderMethod, dataBinder)) {
				/**
				 * 反射执行@InitBinder注解的方法
				 */
				Object returnValue = binderMethod.invokeForRequest(request, null, dataBinder);
				if (returnValue != null) {
					throw new IllegalStateException(
							"@InitBinder methods must not return a value (should be void): " + binderMethod);
				}
			}
		}
	}

	/**
	 * Determine whether the given {@code @InitBinder} method should be used
	 * to initialize the given {@link WebDataBinder} instance. By default we
	 * check the specified attribute names in the annotation value, if any.
	 */
	//过滤掉不符合条件的方法

	/**
	 * 遍历所有标注@InitBinder注解的方法，逐个过滤，判定通过之后才会执行方法
	 *
	 * 如果注解未指定value属性值，判定通过
	 * 如果注解指定了value属性值，那么就判断要绑定参数的参数名，只有和注解上指定的匹配，才会判定通过
	 * @param initBinderMethod
	 * @param dataBinder
	 * @return
	 */
	protected boolean isBinderMethodApplicable(HandlerMethod initBinderMethod, WebDataBinder dataBinder) {
		//获取方法上@InitBinder注解信息
		InitBinder ann = initBinderMethod.getMethodAnnotation(InitBinder.class);
		Assert.state(ann != null, "No InitBinder annotation");
		String[] names = ann.value();
		/**
		 * 如果注解未指定value属性值，则表示所有的绑定器都要执行该方法进行初始化
		 * 如果指定了，那么就会判断要绑定参数的参数名，只有和注解上指定的匹配，才会执行
		 * 该注解方法进行初始化
		 * getObjectName()方法，获取要绑定参数的参数名
		 */
		return (ObjectUtils.isEmpty(names) || ObjectUtils.containsElement(names, dataBinder.getObjectName()));
	}

}
