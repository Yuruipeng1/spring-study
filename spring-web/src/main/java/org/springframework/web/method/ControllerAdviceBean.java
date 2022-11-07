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

package org.springframework.web.method;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Encapsulates information about an {@link ControllerAdvice @ControllerAdvice}
 * Spring-managed bean without necessarily requiring it to be instantiated.
 *
 * <p>The {@link #findAnnotatedBeans(ApplicationContext)} method can be used to
 * discover such beans. However, a {@code ControllerAdviceBean} may be created
 * from any object, including ones without an {@code @ControllerAdvice} annotation.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.2
 */
//封装@ControllerAdvice注解的bean
public class ControllerAdviceBean implements Ordered {

	/**
	 * Reference to the actual bean instance or a {@code String} representing
	 * the bean name.
	 */
	private final Object beanOrName;

	private final boolean isSingleton;

	/**
	 * Reference to the resolved bean instance, potentially lazily retrieved
	 * via the {@code BeanFactory}.
	 */
	@Nullable
	private Object resolvedBean;

	@Nullable
	private final Class<?> beanType;

	private final HandlerTypePredicate beanTypePredicate;

	@Nullable
	private final BeanFactory beanFactory;

	@Nullable
	private Integer order;


	/**
	 * Create a {@code ControllerAdviceBean} using the given bean instance.
	 * @param bean the bean instance
	 */
	public ControllerAdviceBean(Object bean) {
		Assert.notNull(bean, "Bean must not be null");
		this.beanOrName = bean;
		this.isSingleton = true;
		this.resolvedBean = bean;
		this.beanType = ClassUtils.getUserClass(bean.getClass());
		this.beanTypePredicate = createBeanTypePredicate(this.beanType);
		this.beanFactory = null;
	}

	/**
	 * Create a {@code ControllerAdviceBean} using the given bean name and
	 * {@code BeanFactory}.
	 * @param beanName the name of the bean
	 * @param beanFactory a {@code BeanFactory} to retrieve the bean type initially
	 * and later to resolve the actual bean
	 */
	public ControllerAdviceBean(String beanName, BeanFactory beanFactory) {
		this(beanName, beanFactory, null);
	}

	/**
	 * Create a {@code ControllerAdviceBean} using the given bean name,
	 * {@code BeanFactory}, and {@link ControllerAdvice @ControllerAdvice}
	 * annotation.
	 * @param beanName the name of the bean
	 * @param beanFactory a {@code BeanFactory} to retrieve the bean type initially
	 * and later to resolve the actual bean
	 * @param controllerAdvice the {@code @ControllerAdvice} annotation for the
	 * bean, or {@code null} if not yet retrieved
	 * @since 5.2
	 */
	public ControllerAdviceBean(String beanName, BeanFactory beanFactory, @Nullable ControllerAdvice controllerAdvice) {
		Assert.hasText(beanName, "Bean name must contain text");
		Assert.notNull(beanFactory, "BeanFactory must not be null");
		Assert.isTrue(beanFactory.containsBean(beanName), () -> "BeanFactory [" + beanFactory +
				"] does not contain specified controller advice bean '" + beanName + "'");

		//保存beanName
		this.beanOrName = beanName;
		//是否单例
		this.isSingleton = beanFactory.isSingleton(beanName);
		//bean的类型
		this.beanType = getBeanType(beanName, beanFactory);
		//bean对象类型断言
		this.beanTypePredicate = (controllerAdvice != null ? createBeanTypePredicate(controllerAdvice) :
				createBeanTypePredicate(this.beanType));
		this.beanFactory = beanFactory;
	}


	/**
	 * Get the order value for the contained bean.
	 * <p>As of Spring Framework 5.2, the order value is lazily retrieved using
	 * the following algorithm and cached. Note, however, that a
	 * {@link ControllerAdvice @ControllerAdvice} bean that is configured as a
	 * scoped bean &mdash; for example, as a request-scoped or session-scoped
	 * bean &mdash; will not be eagerly resolved. Consequently, {@link Ordered} is
	 * not honored for scoped {@code @ControllerAdvice} beans.
	 * <ul>
	 * <li>If the {@linkplain #resolveBean resolved bean} implements {@link Ordered},
	 * use the value returned by {@link Ordered#getOrder()}.</li>
	 * <li>If the {@linkplain #getBeanType() bean type} is known, use the value returned
	 * by {@link OrderUtils#getOrder(Class, int)} with {@link Ordered#LOWEST_PRECEDENCE}
	 * used as the default order value.</li>
	 * <li>Otherwise use {@link Ordered#LOWEST_PRECEDENCE} as the default, fallback
	 * order value.</li>
	 * </ul>
	 * @see #resolveBean()
	 */
	@Override
	public int getOrder() {
		if (this.order == null) {
			Object resolvedBean = null;
			if (this.beanFactory != null && this.beanOrName instanceof String) {
				String beanName = (String) this.beanOrName;
				String targetBeanName = ScopedProxyUtils.getTargetBeanName(beanName);
				boolean isScopedProxy = this.beanFactory.containsBean(targetBeanName);
				// Avoid eager @ControllerAdvice bean resolution for scoped proxies,
				// since attempting to do so during context initialization would result
				// in an exception due to the current absence of the scope. For example,
				// an HTTP request or session scope is not active during initialization.
				if (!isScopedProxy && !ScopedProxyUtils.isScopedTarget(beanName)) {
					resolvedBean = resolveBean();
				}
			}
			else {
				resolvedBean = resolveBean();
			}

			if (resolvedBean instanceof Ordered) {
				this.order = ((Ordered) resolvedBean).getOrder();
			}
			else if (this.beanType != null) {
				this.order = OrderUtils.getOrder(this.beanType, Ordered.LOWEST_PRECEDENCE);
			}
			else {
				this.order = Ordered.LOWEST_PRECEDENCE;
			}
		}
		return this.order;
	}

	/**
	 * Return the type of the contained bean.
	 * <p>If the bean type is a CGLIB-generated class, the original user-defined
	 * class is returned.
	 */
	@Nullable
	public Class<?> getBeanType() {
		return this.beanType;
	}

	/**
	 * Get the bean instance for this {@code ControllerAdviceBean}, if necessary
	 * resolving the bean name through the {@link BeanFactory}.
	 * <p>As of Spring Framework 5.2, once the bean instance has been resolved it
	 * will be cached if it is a singleton, thereby avoiding repeated lookups in
	 * the {@code BeanFactory}.
	 */
	//获取ControllerAdviceBean对应的bean对象
	public Object resolveBean() {
		if (this.resolvedBean == null) {
			// this.beanOrName must be a String representing the bean name if
			// this.resolvedBean is null.
			//调用工厂的getBean()方法实例化
			Object resolvedBean = obtainBeanFactory().getBean((String) this.beanOrName);
			// Don't cache non-singletons (e.g., prototypes).
			//非单例的就不缓存
			if (!this.isSingleton) {
				return resolvedBean;
			}
			//单例的缓存
			this.resolvedBean = resolvedBean;
		}
		return this.resolvedBean;
	}

	private BeanFactory obtainBeanFactory() {
		Assert.state(this.beanFactory != null, "No BeanFactory set");
		return this.beanFactory;
	}

	/**
	 * Check whether the given bean type should be advised by this
	 * {@code ControllerAdviceBean}.
	 * @param beanType the type of the bean to check
	 * @since 4.0
	 * @see ControllerAdvice
	 */
	public boolean isApplicableToBeanType(@Nullable Class<?> beanType) {
		return this.beanTypePredicate.test(beanType);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ControllerAdviceBean)) {
			return false;
		}
		ControllerAdviceBean otherAdvice = (ControllerAdviceBean) other;
		return (this.beanOrName.equals(otherAdvice.beanOrName) && this.beanFactory == otherAdvice.beanFactory);
	}

	@Override
	public int hashCode() {
		return this.beanOrName.hashCode();
	}

	@Override
	public String toString() {
		return this.beanOrName.toString();
	}


	/**
	 * Find beans annotated with {@link ControllerAdvice @ControllerAdvice} in the
	 * given {@link ApplicationContext} and wrap them as {@code ControllerAdviceBean}
	 * instances.
	 * <p>As of Spring Framework 5.2, the {@code ControllerAdviceBean} instances
	 * in the returned list are sorted using {@link OrderComparator#sort(List)}.
	 * @see #getOrder()
	 * @see OrderComparator
	 * @see Ordered
	 */
	//获取容器中有@ControllerAdvice注解的bean，并封装为ControllerAdviceBean
	public static List<ControllerAdviceBean> findAnnotatedBeans(ApplicationContext context) {
		List<ControllerAdviceBean> adviceBeans = new ArrayList<>();
		//获取容器中所有bean的名字并遍历
		for (String name : BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context, Object.class)) {
			//非代理类（没有使用作用域代理模式，@Scope注解指定）
			if (!ScopedProxyUtils.isScopedTarget(name)) {
				//得到bean中@ControllerAdvice注解信息
				ControllerAdvice controllerAdvice = context.findAnnotationOnBean(name, ControllerAdvice.class);
				//有@ControllerAdvice注解
				if (controllerAdvice != null) {
					// Use the @ControllerAdvice annotation found by findAnnotationOnBean()
					// in order to avoid a subsequent lookup of the same annotation.
					//构建ControllerAdviceBean
					adviceBeans.add(new ControllerAdviceBean(name, context, controllerAdvice));
				}
			}
		}
		//根据PriorityOrdered接口和Order接口排序
		OrderComparator.sort(adviceBeans);
		return adviceBeans;
	}

	@Nullable
	private static Class<?> getBeanType(String beanName, BeanFactory beanFactory) {
		Class<?> beanType = beanFactory.getType(beanName);
		return (beanType != null ? ClassUtils.getUserClass(beanType) : null);
	}

	private static HandlerTypePredicate createBeanTypePredicate(@Nullable Class<?> beanType) {
		ControllerAdvice controllerAdvice = (beanType != null ?
				AnnotatedElementUtils.findMergedAnnotation(beanType, ControllerAdvice.class) : null);
		return createBeanTypePredicate(controllerAdvice);
	}

	//根据@ControllerAdvice注解信息构建一个HandlerTypePredicate
	private static HandlerTypePredicate createBeanTypePredicate(@Nullable ControllerAdvice controllerAdvice) {
		if (controllerAdvice != null) {
			return HandlerTypePredicate.builder()
					.basePackage(controllerAdvice.basePackages())
					.basePackageClass(controllerAdvice.basePackageClasses())
					.assignableType(controllerAdvice.assignableTypes())
					.annotation(controllerAdvice.annotations())
					.build();
		}
		return HandlerTypePredicate.forAnyHandlerType();
	}

}
