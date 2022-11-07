/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.method;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * A {@code Predicate} to match request handling component types if
 * <strong>any</strong> of the following selectors match:
 * <ul>
 * <li>Base packages -- for selecting handlers by their package.
 * <li>Assignable types -- for selecting handlers by super type.
 * <li>Annotations -- for selecting handlers annotated in a specific way.
 * </ul>
 * <p>Composability methods on {@link Predicate} can be used :
 * <pre class="code">
 * Predicate&lt;Class&lt;?&gt;&gt; predicate =
 * 		HandlerTypePredicate.forAnnotation(RestController.class)
 * 				.and(HandlerTypePredicate.forBasePackage("org.example"));
 * </pre>
 *
 * @author Rossen Stoyanchev
 * @since 5.1
 */
//断言器，用来匹配请求处理组件的类型
// 它是专门用来匹配@ControllerAdvice注解指定类的一个断言器，
// 它有一个嵌套类Builder（建造者模式），方便创建一个HandlerTypePredicate对象。

public final class HandlerTypePredicate implements Predicate<Class<?>> {

	//包名
	private final Set<String> basePackages;

	//类型
	private final List<Class<?>> assignableTypes;

	//注解
	private final List<Class<? extends Annotation>> annotations;


	/**
	 * Private constructor. See static factory methods.
	 */
	private HandlerTypePredicate(Set<String> basePackages, List<Class<?>> assignableTypes,
			List<Class<? extends Annotation>> annotations) {

		this.basePackages = Collections.unmodifiableSet(basePackages);
		this.assignableTypes = Collections.unmodifiableList(assignableTypes);
		this.annotations = Collections.unmodifiableList(annotations);
	}


	@Override
	public boolean test(@Nullable Class<?> controllerType) {
		/**
		 * 未在@ControllerAdvice注解中指定生效类
		 * 说明对所有的类都生效
		 */
		if (!hasSelectors()) {
			return true;
		}
		//部分类生效，进行三种方式的判断
		else if (controllerType != null) {
			//包名
			for (String basePackage : this.basePackages) {
				if (controllerType.getName().startsWith(basePackage)) {
					return true;
				}
			}
			//类型
			for (Class<?> clazz : this.assignableTypes) {
				if (ClassUtils.isAssignable(clazz, controllerType)) {
					return true;
				}
			}
			//注解
			for (Class<? extends Annotation> annotationClass : this.annotations) {
				if (AnnotationUtils.findAnnotation(controllerType, annotationClass) != null) {
					return true;
				}
			}
		}
		return false;
	}

	//判断是否配置@ControllerAdvice注解属性
	private boolean hasSelectors() {
		return (!this.basePackages.isEmpty() || !this.assignableTypes.isEmpty() || !this.annotations.isEmpty());
	}


	// Static factory methods

	/**
	 * {@code Predicate} that applies to any handlers.
	 */
	public static HandlerTypePredicate forAnyHandlerType() {
		return new HandlerTypePredicate(
				Collections.emptySet(), Collections.emptyList(), Collections.emptyList());
	}

	/**
	 * Match handlers declared under a base package, e.g. "org.example".
	 * @param packages one or more base package names
	 */
	public static HandlerTypePredicate forBasePackage(String... packages) {
		return new Builder().basePackage(packages).build();
	}

	/**
	 * Type-safe alternative to {@link #forBasePackage(String...)} to specify a
	 * base package through a class.
	 * @param packageClasses one or more base package classes
	 */
	public static HandlerTypePredicate forBasePackageClass(Class<?>... packageClasses) {
		return new Builder().basePackageClass(packageClasses).build();
	}

	/**
	 * Match handlers that are assignable to a given type.
	 * @param types one or more handler super types
	 */
	public static HandlerTypePredicate forAssignableType(Class<?>... types) {
		return new Builder().assignableType(types).build();
	}

	/**
	 * Match handlers annotated with a specific annotation.
	 * @param annotations one or more annotations to check for
	 */
	@SafeVarargs
	public static HandlerTypePredicate forAnnotation(Class<? extends Annotation>... annotations) {
		return new Builder().annotation(annotations).build();
	}

	/**
	 * Return a builder for a {@code HandlerTypePredicate}.
	 */
	public static Builder builder() {
		return new Builder();
	}


	/**
	 * A {@link HandlerTypePredicate} builder.
	 */
	public static class Builder {

		private final Set<String> basePackages = new LinkedHashSet<>();

		private final List<Class<?>> assignableTypes = new ArrayList<>();

		private final List<Class<? extends Annotation>> annotations = new ArrayList<>();

		/**
		 * Match handlers declared under a base package, e.g. "org.example".
		 * @param packages one or more base package classes
		 */
		public Builder basePackage(String... packages) {
			Arrays.stream(packages).filter(StringUtils::hasText).forEach(this::addBasePackage);
			return this;
		}

		/**
		 * Type-safe alternative to {@link #forBasePackage(String...)} to specify a
		 * base package through a class.
		 * @param packageClasses one or more base package names
		 */
		public Builder basePackageClass(Class<?>... packageClasses) {
			Arrays.stream(packageClasses).forEach(clazz -> addBasePackage(ClassUtils.getPackageName(clazz)));
			return this;
		}

		private void addBasePackage(String basePackage) {
			this.basePackages.add(basePackage.endsWith(".") ? basePackage : basePackage + ".");
		}

		/**
		 * Match handlers that are assignable to a given type.
		 * @param types one or more handler super types
		 */
		public Builder assignableType(Class<?>... types) {
			this.assignableTypes.addAll(Arrays.asList(types));
			return this;
		}

		/**
		 * Match types that are annotated with one of the given annotations.
		 * @param annotations one or more annotations to check for
		 */
		@SuppressWarnings("unchecked")
		public final Builder annotation(Class<? extends Annotation>... annotations) {
			this.annotations.addAll(Arrays.asList(annotations));
			return this;
		}

		public HandlerTypePredicate build() {
			return new HandlerTypePredicate(this.basePackages, this.assignableTypes, this.annotations);
		}
	}

}
