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

package org.springframework.beans.factory.annotation;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that invokes annotated init and destroy methods. Allows for an annotation
 * alternative to Spring's {@link org.springframework.beans.factory.InitializingBean}
 * and {@link org.springframework.beans.factory.DisposableBean} callback interfaces.
 *
 * <p>The actual annotation types that this post-processor checks for can be
 * configured through the {@link #setInitAnnotationType "initAnnotationType"}
 * and {@link #setDestroyAnnotationType "destroyAnnotationType"} properties.
 * Any custom annotation can be used, since there are no required annotation
 * attributes.
 *
 * <p>Init and destroy annotations may be applied to methods of any visibility:
 * public, package-protected, protected, or private. Multiple such methods
 * may be annotated, but it is recommended to only annotate one single
 * init method and destroy method, respectively.
 *
 * <p>Spring's {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor}
 * supports the JSR-250 {@link javax.annotation.PostConstruct} and {@link javax.annotation.PreDestroy}
 * annotations out of the box, as init annotation and destroy annotation, respectively.
 * Furthermore, it also supports the {@link javax.annotation.Resource} annotation
 * for annotation-driven injection of named beans.
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see #setInitAnnotationType
 * @see #setDestroyAnnotationType
 */
@SuppressWarnings("serial")
public class InitDestroyAnnotationBeanPostProcessor
		implements DestructionAwareBeanPostProcessor, MergedBeanDefinitionPostProcessor, PriorityOrdered, Serializable {

	private final transient LifecycleMetadata emptyLifecycleMetadata =
			new LifecycleMetadata(Object.class, Collections.emptyList(), Collections.emptyList()) {
				@Override
				public void checkConfigMembers(RootBeanDefinition beanDefinition) {
				}
				@Override
				public void invokeInitMethods(Object target, String beanName) {
				}
				@Override
				public void invokeDestroyMethods(Object target, String beanName) {
				}
				@Override
				public boolean hasDestroyMethods() {
					return false;
				}
			};


	protected transient Log logger = LogFactory.getLog(getClass());

	@Nullable
	private Class<? extends Annotation> initAnnotationType;

	@Nullable
	private Class<? extends Annotation> destroyAnnotationType;

	private int order = Ordered.LOWEST_PRECEDENCE;

	@Nullable
	private final transient Map<Class<?>, LifecycleMetadata> lifecycleMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Specify the init annotation to check for, indicating initialization
	 * methods to call after configuration of a bean.
	 * <p>Any custom annotation can be used, since there are no required
	 * annotation attributes. There is no default, although a typical choice
	 * is the JSR-250 {@link javax.annotation.PostConstruct} annotation.
	 */
	public void setInitAnnotationType(Class<? extends Annotation> initAnnotationType) {
		this.initAnnotationType = initAnnotationType;
	}

	/**
	 * Specify the destroy annotation to check for, indicating destruction
	 * methods to call when the context is shutting down.
	 * <p>Any custom annotation can be used, since there are no required
	 * annotation attributes. There is no default, although a typical choice
	 * is the JSR-250 {@link javax.annotation.PreDestroy} annotation.
	 */
	public void setDestroyAnnotationType(Class<? extends Annotation> destroyAnnotationType) {
		this.destroyAnnotationType = destroyAnnotationType;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		//找到beanType类的生命周期元数据
		LifecycleMetadata metadata = findLifecycleMetadata(beanType);
		//将生命周期元数据保存到beanDefinition中
		metadata.checkConfigMembers(beanDefinition);
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		//获取所有生命周期元数据
		LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
		try {
			//执行生命周期初始化方法
			metadata.invokeInitMethods(bean, beanName);
		}
		catch (InvocationTargetException ex) {
			throw new BeanCreationException(beanName, "Invocation of init method failed", ex.getTargetException());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Failed to invoke init method", ex);
		}
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) throws BeansException {
		LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
		try {
			metadata.invokeDestroyMethods(bean, beanName);
		}
		catch (InvocationTargetException ex) {
			String msg = "Destroy method on bean with name '" + beanName + "' threw an exception";
			if (logger.isDebugEnabled()) {
				logger.warn(msg, ex.getTargetException());
			}
			else {
				logger.warn(msg + ": " + ex.getTargetException());
			}
		}
		catch (Throwable ex) {
			logger.warn("Failed to invoke destroy method on bean with name '" + beanName + "'", ex);
		}
	}

	@Override
	public boolean requiresDestruction(Object bean) {
		return findLifecycleMetadata(bean.getClass()).hasDestroyMethods();
	}


	private LifecycleMetadata findLifecycleMetadata(Class<?> clazz) {
		/**
		 * 先查缓存中有没有生命周期元数据
		 * this.lifecycleMetadataCache是一个map集合，它的key就是当前类的clazz
		 * value是当前类的生命周期元数据
		 */
		if (this.lifecycleMetadataCache == null) {
			// Happens after deserialization, during destruction...
			//直接去获取生命周期元数据，重点方法
			return buildLifecycleMetadata(clazz);
		}
		// Quick check on the concurrent map first, with minimal locking.
		LifecycleMetadata metadata = this.lifecycleMetadataCache.get(clazz);
		if (metadata == null) {
			synchronized (this.lifecycleMetadataCache) {
				metadata = this.lifecycleMetadataCache.get(clazz);
				if (metadata == null) {
					//直接去获取生命周期元数据
					metadata = buildLifecycleMetadata(clazz);
					//缓存，key就是当前类的clazz
					this.lifecycleMetadataCache.put(clazz, metadata);
				}
				return metadata;
			}
		}
		return metadata;
	}

	private LifecycleMetadata buildLifecycleMetadata(final Class<?> clazz) {
		/**
		 * this.initAnnotationType为PostConstruct.class
		 * this.destroyAnnotationType为PreDestroy.class
		 * 在CommonAnnotationBeanPostProcessor默认的构造方法中赋值
		 * AnnotationUtils.isCandidateClass()是判断clazz中是否存在PostConstruct和PreDestroy注解
		 */
		if (!AnnotationUtils.isCandidateClass(clazz, Arrays.asList(this.initAnnotationType, this.destroyAnnotationType))) {
			//不存在PostConstruct和PreDestroy注解，直接返回一个空的生命周期元数据
			return this.emptyLifecycleMetadata;
		}

		List<LifecycleElement> initMethods = new ArrayList<>();
		List<LifecycleElement> destroyMethods = new ArrayList<>();
		Class<?> targetClass = clazz;

		do {
			final List<LifecycleElement> currInitMethods = new ArrayList<>();
			final List<LifecycleElement> currDestroyMethods = new ArrayList<>();

			/**
			 * ReflectionUtils.doWithLocalMethods()方法很简单，其实就是遍历targetClass所有的
			 * 方法，将它作为参数回调接口方法。这个方法我在说@Autowired原理的时候已经详细解释过了，这里
			 * 不在多费唇舌
			 * 真正的处理逻辑在参数2的lamada表达式中
			 */
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				/****************************处理@PostConstruct注解******************************/
				//method.isAnnotationPresent()判断方法上有没有指定的注解（反射的知识）
				if (this.initAnnotationType != null && method.isAnnotationPresent(this.initAnnotationType)) {
					//构建LifecycleElement
					LifecycleElement element = new LifecycleElement(method);
					//加入到初始化方法集合中
					currInitMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Found init method on class [" + clazz.getName() + "]: " + method);
					}
				}
				/****************************处理@PreDestroy注解******************************/
				if (this.destroyAnnotationType != null && method.isAnnotationPresent(this.destroyAnnotationType)) {
					currDestroyMethods.add(new LifecycleElement(method));
					if (logger.isTraceEnabled()) {
						logger.trace("Found destroy method on class [" + clazz.getName() + "]: " + method);
					}
				}
			});

			initMethods.addAll(0, currInitMethods);
			destroyMethods.addAll(currDestroyMethods);
			//获取父类，因为父类中也有可能指定了生命周期方法
			targetClass = targetClass.getSuperclass();
		}
		while (targetClass != null && targetClass != Object.class);
		//返回声明周期元数据
		return (initMethods.isEmpty() && destroyMethods.isEmpty() ? this.emptyLifecycleMetadata :
				//构建LifecycleMetadata
				new LifecycleMetadata(clazz, initMethods, destroyMethods));
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * Class representing information about annotated init and destroy methods.
	 */
	private class LifecycleMetadata {

		private final Class<?> targetClass;

		private final Collection<LifecycleElement> initMethods;

		private final Collection<LifecycleElement> destroyMethods;

		@Nullable
		private volatile Set<LifecycleElement> checkedInitMethods;

		@Nullable
		private volatile Set<LifecycleElement> checkedDestroyMethods;

		public LifecycleMetadata(Class<?> targetClass, Collection<LifecycleElement> initMethods,
				Collection<LifecycleElement> destroyMethods) {

			//指定当前生命周期元数据所属的类
			this.targetClass = targetClass;
			//指定当前生命周期元数据中类的初始化方法
			this.initMethods = initMethods;
			//指定当前生命周期元数据中类的销毁方法
			this.destroyMethods = destroyMethods;
		}

		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
			/****************************缓存初始化方法******************************/
			Set<LifecycleElement> checkedInitMethods = new LinkedHashSet<>(this.initMethods.size());
			for (LifecycleElement element : this.initMethods) {
				//要么是完全限定名，要么是方法名
				String methodIdentifier = element.getIdentifier();
				//判断methodIdentifier是否已经被指定为初始化方法
				if (!beanDefinition.isExternallyManagedInitMethod(methodIdentifier)) {
					//没有就将该方法注册为初始化方法
					beanDefinition.registerExternallyManagedInitMethod(methodIdentifier);
					//添加到已检查的方法集合中
					checkedInitMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Registered init method on class [" + this.targetClass.getName() + "]: " + element);
					}
				}
			}
			/****************************缓存初销毁方法******************************/
			Set<LifecycleElement> checkedDestroyMethods = new LinkedHashSet<>(this.destroyMethods.size());
			//流程和上述一致
			for (LifecycleElement element : this.destroyMethods) {
				String methodIdentifier = element.getIdentifier();
				if (!beanDefinition.isExternallyManagedDestroyMethod(methodIdentifier)) {
					beanDefinition.registerExternallyManagedDestroyMethod(methodIdentifier);
					checkedDestroyMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Registered destroy method on class [" + this.targetClass.getName() + "]: " + element);
					}
				}
			}
			this.checkedInitMethods = checkedInitMethods;
			this.checkedDestroyMethods = checkedDestroyMethods;
		}

		public void invokeInitMethods(Object target, String beanName) throws Throwable {
			Collection<LifecycleElement> checkedInitMethods = this.checkedInitMethods;
			Collection<LifecycleElement> initMethodsToIterate =
					(checkedInitMethods != null ? checkedInitMethods : this.initMethods);
			if (!initMethodsToIterate.isEmpty()) {
				for (LifecycleElement element : initMethodsToIterate) {
					if (logger.isTraceEnabled()) {
						logger.trace("Invoking init method on bean '" + beanName + "': " + element.getMethod());
					}
					//执行LifecycleElement的invoke方法
					element.invoke(target);
				}
			}
		}

		public void invokeDestroyMethods(Object target, String beanName) throws Throwable {
			Collection<LifecycleElement> checkedDestroyMethods = this.checkedDestroyMethods;
			Collection<LifecycleElement> destroyMethodsToUse =
					(checkedDestroyMethods != null ? checkedDestroyMethods : this.destroyMethods);
			if (!destroyMethodsToUse.isEmpty()) {
				for (LifecycleElement element : destroyMethodsToUse) {
					if (logger.isTraceEnabled()) {
						logger.trace("Invoking destroy method on bean '" + beanName + "': " + element.getMethod());
					}
					element.invoke(target);
				}
			}
		}

		public boolean hasDestroyMethods() {
			Collection<LifecycleElement> checkedDestroyMethods = this.checkedDestroyMethods;
			Collection<LifecycleElement> destroyMethodsToUse =
					(checkedDestroyMethods != null ? checkedDestroyMethods : this.destroyMethods);
			return !destroyMethodsToUse.isEmpty();
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private static class LifecycleElement {

		private final Method method;

		private final String identifier;

		public LifecycleElement(Method method) {
			//生命周期方法不能有参数
			if (method.getParameterCount() != 0) {
				throw new IllegalStateException("Lifecycle method annotation requires a no-arg method: " + method);
			}
			this.method = method;
			/**
			 * 如果生命周期方法是私有的，identifier就是类或接口的完全限定名+"."+方法名
			 * 否则identifier就是方法名
			 */
			this.identifier = (Modifier.isPrivate(method.getModifiers()) ?
					ClassUtils.getQualifiedMethodName(method) : method.getName());
		}

		public Method getMethod() {
			return this.method;
		}

		public String getIdentifier() {
			return this.identifier;
		}

		public void invoke(Object target) throws Throwable {
			//反射执行生命周期初始化方法
			ReflectionUtils.makeAccessible(this.method);
			this.method.invoke(target, (Object[]) null);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof LifecycleElement)) {
				return false;
			}
			LifecycleElement otherElement = (LifecycleElement) other;
			return (this.identifier.equals(otherElement.identifier));
		}

		@Override
		public int hashCode() {
			return this.identifier.hashCode();
		}
	}

}
