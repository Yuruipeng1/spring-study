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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Internal class for managing injection metadata.
 * Not intended for direct use in applications.
 *
 * <p>Used by {@link AutowiredAnnotationBeanPostProcessor},
 * {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor} and
 * {@link org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor}.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
public class InjectionMetadata {

	/**
	 * An empty {@code InjectionMetadata} instance with no-op callbacks.
	 * @since 5.2
	 */
	public static final InjectionMetadata EMPTY = new InjectionMetadata(Object.class, Collections.emptyList()) {
		@Override
		protected boolean needsRefresh(Class<?> clazz) {
			return false;
		}
		@Override
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		}
		@Override
		public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) {
		}
		@Override
		public void clear(@Nullable PropertyValues pvs) {
		}
	};


	private final Class<?> targetClass;

	private final Collection<InjectedElement> injectedElements;

	@Nullable
	private volatile Set<InjectedElement> checkedElements;


	/**
	 * Create a new {@code InjectionMetadata instance}.
	 * <p>Preferably use {@link #forElements} for reusing the {@link #EMPTY}
	 * instance in case of no elements.
	 * @param targetClass the target class
	 * @param elements the associated elements to inject
	 * @see #forElements
	 */
	public InjectionMetadata(Class<?> targetClass, Collection<InjectedElement> elements) {
		this.targetClass = targetClass;
		this.injectedElements = elements;
	}


	/**
	 * Determine whether this metadata instance needs to be refreshed.
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @since 5.2.4
	 */
	protected boolean needsRefresh(Class<?> clazz) {
		return this.targetClass != clazz;
	}

	/**
	 * 将注入（注解）元数据保存到beanDefinition中
	 * @param beanDefinition
	 */
	public void checkConfigMembers(RootBeanDefinition beanDefinition) {
		Set<InjectedElement> checkedElements = new LinkedHashSet<>(this.injectedElements.size());
		//遍历
		for (InjectedElement element : this.injectedElements) {
			//获取被注入元素的属性对象
			Member member = element.getMember();
			//将当前需要自动注入的属性或方法保存到BeanDefinition中
			if (!beanDefinition.isExternallyManagedConfigMember(member)) {
				beanDefinition.registerExternallyManagedConfigMember(member);
				checkedElements.add(element);
			}
		}
		this.checkedElements = checkedElements;
	}

	public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
		//已经记录的需要自动注入的属性
		Collection<InjectedElement> checkedElements = this.checkedElements;
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		if (!elementsToIterate.isEmpty()) {
			//遍历获取每个需要被注入的元素（属性或方法）
			for (InjectedElement element : elementsToIterate) {
				//无论是属性或方法都封装为ResourceElement
				element.inject(target, beanName, pvs);
			}
		}
	}

	/**
	 * Clear property skipping for the contained elements.
	 * @since 3.2.13
	 */
	public void clear(@Nullable PropertyValues pvs) {
		Collection<InjectedElement> checkedElements = this.checkedElements;
		Collection<InjectedElement> elementsToIterate =
				(checkedElements != null ? checkedElements : this.injectedElements);
		if (!elementsToIterate.isEmpty()) {
			for (InjectedElement element : elementsToIterate) {
				element.clearPropertySkipping(pvs);
			}
		}
	}


	/**
	 * Return an {@code InjectionMetadata} instance, possibly for empty elements.
	 * @param elements the elements to inject (possibly empty)
	 * @param clazz the target class
	 * @return a new {@link #InjectionMetadata(Class, Collection)} instance
	 * @since 5.2
	 */
	//根据注解信息构建注入元数据
	public static InjectionMetadata forElements(Collection<InjectedElement> elements, Class<?> clazz) {
		return (elements.isEmpty() ? new InjectionMetadata(clazz, Collections.emptyList()) :
				new InjectionMetadata(clazz, elements));
	}

	/**
	 * Check whether the given injection metadata needs to be refreshed.
	 * @param metadata the existing metadata instance
	 * @param clazz the current target class
	 * @return {@code true} indicating a refresh, {@code false} otherwise
	 * @see #needsRefresh(Class)
	 */
	public static boolean needsRefresh(@Nullable InjectionMetadata metadata, Class<?> clazz) {
		return (metadata == null || metadata.needsRefresh(clazz));
	}


	/**
	 * A single injected element.
	 */
	public abstract static class InjectedElement {

		protected final Member member;

		protected final boolean isField;

		@Nullable
		protected final PropertyDescriptor pd;

		@Nullable
		protected volatile Boolean skip;

		protected InjectedElement(Member member, @Nullable PropertyDescriptor pd) {
			this.member = member;
			this.isField = (member instanceof Field);
			this.pd = pd;
		}

		public final Member getMember() {
			return this.member;
		}

		/**
		 * 获取注入类型
		 * @return
		 */
		protected final Class<?> getResourceType() {
			//获取属性类型(字段)
			if (this.isField) {
				return ((Field) this.member).getType();
			}
			//方法是对应属性的get或set方法，此时获取属性类型
			else if (this.pd != null) {
				return this.pd.getPropertyType();
			}
			//获取方法第一个参数类型
			else {
				return ((Method) this.member).getParameterTypes()[0];
			}
		}

		/**
		 * 检查指定的类型resourceType是否匹配属性或方法参数
		 * @param resourceType
		 */
		protected final void checkResourceType(Class<?> resourceType) {


			/*******************************属性**********************************/

			if (this.isField) {
				Class<?> fieldType = ((Field) this.member).getType();
				//指定的类型和属性类型必须包含继承关系，否则抛异常
				if (!(resourceType.isAssignableFrom(fieldType) || fieldType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified field type [" + fieldType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}

			/*******************************方法**********************************/

			else {

				/**
				 * this.pd就是PropertyDescriptor，在上面提到过
				 * 当有@Resource注解的方法是当前类某个属性的get或set方法时，
				 * 就返回这个属性的属性描述PropertyDescriptor，然后设置到ResourceElement中
				 * 所以：
				 * 如果该方法是某个属性的get或set方法，就检查这个属性的类型和指定类型的继承关系
				 * 否则就检查方法第一个参数的类型和指定类型的继承关系
				 */

				Class<?> paramType =
						(this.pd != null ? this.pd.getPropertyType() : ((Method) this.member).getParameterTypes()[0]);
				if (!(resourceType.isAssignableFrom(paramType) || paramType.isAssignableFrom(resourceType))) {
					throw new IllegalStateException("Specified parameter type [" + paramType +
							"] is incompatible with resource type [" + resourceType.getName() + "]");
				}
			}
		}

		/**
		 * Either this or {@link #getResourceToInject} needs to be overridden.
		 */

		/**
		 * 调用ResourceElement的inject方法，实现byName自动注入
		 * @param target
		 * @param requestingBeanName
		 * @param pvs
		 * @throws Throwable
		 */
		protected void inject(Object target, @Nullable String requestingBeanName, @Nullable PropertyValues pvs)
				throws Throwable {

			/*********************************属性******************************/
			if (this.isField) {
				Field field = (Field) this.member;
				//不需要set方法，直接强行赋值
				ReflectionUtils.makeAccessible(field);
				//getResourceToInject(target, requestingBeanName)重点是这个方法
				field.set(target, getResourceToInject(target, requestingBeanName));
			}

			/*********************************方法******************************/
			else {
				if (checkPropertySkipping(pvs)) {
					return;
				}
				try {
					Method method = (Method) this.member;
					//不管方法的修饰符，强行执行方法
					ReflectionUtils.makeAccessible(method);
					method.invoke(target, getResourceToInject(target, requestingBeanName));
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * Check whether this injector's property needs to be skipped due to
		 * an explicit property value having been specified. Also marks the
		 * affected property as processed for other processors to ignore it.
		 */
		protected boolean checkPropertySkipping(@Nullable PropertyValues pvs) {
			Boolean skip = this.skip;
			if (skip != null) {
				return skip;
			}
			if (pvs == null) {
				this.skip = false;
				return false;
			}
			synchronized (pvs) {
				skip = this.skip;
				if (skip != null) {
					return skip;
				}
				if (this.pd != null) {
					if (pvs.contains(this.pd.getName())) {
						// Explicit value provided as part of the bean definition.
						this.skip = true;
						return true;
					}
					else if (pvs instanceof MutablePropertyValues) {
						((MutablePropertyValues) pvs).registerProcessedProperty(this.pd.getName());
					}
				}
				this.skip = false;
				return false;
			}
		}

		/**
		 * Clear property skipping for this element.
		 * @since 3.2.13
		 */
		protected void clearPropertySkipping(@Nullable PropertyValues pvs) {
			if (pvs == null) {
				return;
			}
			synchronized (pvs) {
				if (Boolean.FALSE.equals(this.skip) && this.pd != null && pvs instanceof MutablePropertyValues) {
					((MutablePropertyValues) pvs).clearProcessedProperty(this.pd.getName());
				}
			}
		}

		/**
		 * Either this or {@link #inject} needs to be overridden.
		 */
		@Nullable
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			return null;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof InjectedElement)) {
				return false;
			}
			InjectedElement otherElement = (InjectedElement) other;
			return this.member.equals(otherElement.member);
		}

		@Override
		public int hashCode() {
			return this.member.getClass().hashCode() * 29 + this.member.getName().hashCode();
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + " for " + this.member;
		}
	}

}
