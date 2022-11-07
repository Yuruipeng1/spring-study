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

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/** Strategy for creating bean instances. */
	// bean的生成策略，默认CGLIB
	private InstantiationStrategy instantiationStrategy = new CglibSubclassingInstantiationStrategy();

	/** Resolver strategy for method parameter names. */
	@Nullable
	// 解析策略的方法参数
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	// 尝试解析循环引用
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	// 在循环引用的情况下，是否需要注入一个原始的bean实例
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	// 依赖项检查和自动装配时忽略的依赖项类型
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	// 依赖项检查和自动装配时忽略的依赖项接口
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	// 当前正在创建的bean
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	// beanName和FactoryBean的映射
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	// 类和候选方法映射
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	// 类和PropertyDescriptor的映射
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	//在循环引用的情况下，是否需要注入一个原始的bean实例
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	//依赖项检查和自动装配时忽略的依赖项类型
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	//依赖项检查和自动装配时忽略的依赖项接口。这个作用是这样的：比如A中有属性B，正常A初始化的时候，
	// 如果B没有初始化，这个时候，会初始化B。比如构造函数设置了几个Aware接口，就不会在作为属性B的时候初始化。
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	//除了复制父类的几种配置，还包括了
	// instantiationStrategy、allowCircularReferences、ignoredDependencyTypes、ignoredDependencyInterfaces
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		// 封装到BeanDefinition
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		// 是否允许缓存
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	//给bean的属性赋值
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		// 初始化BeanWrapper
		initBeanWrapper(bw);
		// 给bean的属性赋值
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		// 如果已经创建了，bean的定义要清除
		markBeanAsCreated(beanName);
		// 重新设置bean的定义
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		// 初始化BeanWrapper
		initBeanWrapper(bw);
		// 给bean的属性赋值
		populateBean(beanName, bd, bw);
		// 调用init方法，InitializingBean这些
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	//使用指定的自动装配策略实例化给定类的新bean实例
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		// 如果是构造器注入
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(bd, null, this),
						getAccessControlContext());
			}
			else {
				// 生成bean
				bean = getInstantiationStrategy().instantiate(bd, null, this);
			}
			// 给bean的属性赋值
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	//根据名称或类型自动装配给定bean实例的bean属性。
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	//将具有给定名称的bean定义的属性值应用于给定的bean实例
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	@Override
	//前置处理器
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		// 获取BeanPostProcessor处理
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	@Override
	//后置处理器
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		// 获取BeanPostProcessor处理
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			Object current = processor.postProcessAfterInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(existingBean, getBeanPostProcessors(), getAccessControlContext()).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	//解析给定bean名称的bean实例，为目标工厂方法提供依赖描述符。
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			// 获取bean
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			// 为目标工厂方法提供依赖描述符
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	//根据工厂中定义的bean解析指定的依赖项。
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 * @see #doCreateBean
	 */
	/**
	 * 1.解析class类型
	 *
	 * 2.如果有lookup-method和replaced-method属性先处理。如果有某个属性，就调用动态代理，进行相应的拦截。
	 * 另外，有些方法可能存在重载的情况，在这里也会一并处理了，如果只有一个方法名，会标记为没有重载，
	 * 这样后面调用方法的时候，就可以直接调用了。
	 *
	 * 3.resolveBeforeInstantiation，先判断是否解析过了，
	 * 如果没有，看看InstantiationAwareBeanPostProcessor能不能生成bean--applyBeanPostProcessorsBeforeInitialization，
	 * 这个bean不一定是普通的bean，也可能是动态代理或者其他方式生成的bean。如果可以生成bean，就标志位解析过了，
	 * 并且调用后置处理器处理--applyBeanPostProcessorsAfterInitialization。
	 *
	 * 4.如果没有生成，调用doCreateBean方法
	 *
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return
	 * @throws BeanCreationException
	 * ObjectFactory接口方法通过调用createBean方法实例化对象
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		//解析用于当前创建bean的class对象
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		/**
		 * 类名中包含了SpEL表达式，此时是动态加载到虚拟机的，不能将clazz对象保存到BeanDefinition中
		 * 应为SpEL表达式的值可以改变，所以每次需要clazz对象的时候，应该重新解析。
		 * 此处重新拷贝一份BeanDefinition，然后将动态解析到clazz对象设置进去，
		 * 不改变原来的BeanDefinition。
		 */
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// 不为空，且mbd没有类信息，重新设值
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		try {
			// 之前的lookup注解或者<lookup-method /> 标签就是这里实现的，还有<replaced-method />
			mbdToUse.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			//InstantiationAwareBeanPostProcessor返回一个代理对象来代替目标对象
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			//创建了代理对象，直接返回,跳过后面的生命周期流程
			if (bean != null) {
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		//经过上面一系列判断之后，开始创建bean
		try {
			//实例化bean
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		}
		catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// Instantiate the bean.
		BeanWrapper instanceWrapper = null;
		//1.如果是单例，取出bean并清空缓存。
		if (mbd.isSingleton()) {
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		//2.如果取出的bean为空，实例化bean
		if (instanceWrapper == null) {
			//用反射实例化对象
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		//获取真正的实例
		Object bean = instanceWrapper.getWrappedInstance();
		//实际就是bean.getClass()
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			//设置已解析的bean的类型
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		/**
		 * 应用MergedBeanDefinitionPostProcessor的postProcessMergedBeanDefinition方法
		 * 修改当前bean的BeanDefinition
		 */
		synchronized (mbd.postProcessingLock) {
			if (!mbd.postProcessed) {
				try {
					//缓存所有的@Autowired的注解的Member(filed method)
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				//设置已经被MergedBeanDefinitionPostProcessor处理过了
				mbd.postProcessed = true;
			}
		}

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		//spring为了解决循环依赖而做的工作
		/**
		 * 判断是否需要提前将该bean实例暴露
		 * isSingletonCurrentlyInCreation(beanName) 判断当前beanName对应的bean是否正在创建
		 * 只会暴露当前正在创建的单例bean
		 */
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			/**
			 * 创建一个ObjectFactory，它的getObject方法返回的是经过getEarlyBeanReference方法
			 * 增强的bean（此时的bean才刚实例化完成，还没有经过属性填充和初始化）
			 * 并把这个ObjectFactory工厂放入第三级缓存中
			 */
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		Object exposedObject = bean;
		try {
			//bean实例填充属性
			populateBean(beanName, mbd, instanceWrapper);
			//初始化bean,主要执行各种生命周期回调方法以及aop等
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			}
			else {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}

		//到这里其实bean的生命周期基本走完
		//允许提前暴露单例bean
		if (earlySingletonExposure) {
			/**
			 * allowEarlyReference为false，表示不允许使用ObjectFactory来获取早期的单例对象
			 * getSingleton(beanName, false)从1,2,3级缓存中获取beanName对应的对象
			 * 注意，第3级缓存中保存的是ObjectFactory，此时被禁用了
			 */
			Object earlySingletonReference = getSingleton(beanName, false);
			if (earlySingletonReference != null) {
				if (exposedObject == bean) {
					//如果相同则直接赋值表示没有问题
					exposedObject = earlySingletonReference;
				}
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						//如果不相同则出异常
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		/**
		 * 会将有销毁方法的beanName和bean保存到一个map集合中
		 * 方便执行销毁方法
		 */
		try {
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}

		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					Class<?> predicted = ibp.predictBeanType(targetType, beanName);
					if (predicted != null &&
							(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
						return predicted;
					}
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	//获取给定BeanDefinition的目标类型
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		//普通bean
		Class<?> targetType = mbd.getTargetType();
		//工厂方法创建的bean
		if (targetType == null) {
			targetType = (mbd.getFactoryMethodName() != null ?
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			}
			else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				return null;
			}
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					else {
						uniqueCandidate = (commonType == null ? candidate : null);
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		mbd.factoryMethodReturnType = cachedReturnType;
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				}
				else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	@Deprecated
	@Nullable
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	//获取指定bean的早期访问引用，通常用于解析循环引用。
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					//可以调用getEarlyBeanReference对实例化后的bean增强
					exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
				}
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			// 是否已经实例化
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				// 实例化直接返回
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			// factoryBeanInstanceCache没有，看看是否已经创建
			Object beanInstance = getSingleton(beanName, false);
			// 创建好的单例是FactoryBean，直接返回
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			// 创建好的单例不是FactoryBean，或者已经创建或者正在创建，返回空
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				// 创建前检查
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				// 看看代理能不能返回一个实例
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					// 代理没返回，就创建一个
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				// 创建后检查
				afterSingletonCreation(beanName);
			}
			// 获取到的实例转换为FactoryBean
			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				// 放入缓存
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	//不是单例的话，获取FactoryBean，比单例少的步骤是factoryBeanInstanceCache缓冲的操作
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			if (bp instanceof MergedBeanDefinitionPostProcessor) {
				MergedBeanDefinitionPostProcessor bdp = (MergedBeanDefinitionPostProcessor) bp;
				bdp.postProcessMergedBeanDefinition(mbd, beanType, beanName);
			}
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	@Nullable
	//InstantiationAwareBeanPostProcessor返回一个代理对象来代替目标对象，并跳过后续的生命周期
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		/**
		 * mbd.beforeInstantiationResolved,true标识该bean已经经过
		 * InstantiationAwareBeanPostProcessor处理
		 */
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			/**
			 * 容器中有InstantiationAwareBeanPostProcessor，执行该对象的
			 * postProcessBeforeInstantiation方法
			 */

			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				//获取给定BeanDefinition的目标类型
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					/**
					 * 这个方法返回一个代理对象
					 * 我们完全可以在这个InstantiationAwareBeanPostProcessor的
					 * postProcessBeforeInstantiation方法中自己创建一个代理对象，
					 * 然后返回.事实上，spring的aop就是这么做的
					 */
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					if (bean != null) {
						/**
						 * 如果如果成功创建了一个代理对象，就执行InstantiationAwareBeanPostProcessor
						 * 的postProcessAfterInitialization方法,此方法中给它填充属性值
						 */
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			//设置该bean已经经过InstantiationAwareBeanPostProcessor处理
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	//尝试初始化
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			if (bp instanceof InstantiationAwareBeanPostProcessor) {
				InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
				Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
				if (result != null) {
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		// 解析beanClass
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		// 判断是否有类的访问权限
		/**
		 * Modifier.isPublic(beanClass.getModifiers()) 判断当前类的访问修饰符是不是public
		 * mbd.isNonPublicAccessAllowed() 是否允许spring使用非public的构造方法实例化对象，
		 * 或访问普通方法
		 */
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		// 是否有其他回调
		//Supplier是JDK中定义的一个结果的提供者，类似于spring中FactoryBean
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		if (instanceSupplier != null) {
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		// 是否有工厂方法回调,工厂方法实例化对象
		if (mbd.getFactoryMethodName() != null) {
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// Shortcut when re-creating the same bean...
		/***************************重新创建bean的快捷方式*******************************/
		/**
		 * 对象只要被创建过一次，那么创建这个对象使用的构造方法和参数就会被缓存起来
		 * 方便下次快速创建这个bean,对于prototype作用域的bean很有用，节省了时间
		 */
		boolean resolved = false;
		// 为false说明无参，true有参
		boolean autowireNecessary = false;
		//指定构造方法参数就不能使用快速创建bean了
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				/**
				 * 工厂方法或构造方法不为空
				 * 说明当前bean使用的构造方法之前已经解析过了，缓存了
				 * 现在直接使用缓存的构造方法和参数快速实例化对象
				 */
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					resolved = true;
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		// 解析过了，根据是否有参数选择无参构造还是有参构造
		if (resolved) {
			//构造方法参数也解析过了
			if (autowireNecessary) {
				// 有参构造
				return autowireConstructor(beanName, mbd, null, null);
			}
			//构造方法参数未解析，使用默认的构造方法实例化对象
			else {
				//无参构造
				return instantiateBean(beanName, mbd);
			}
		}

		/********************************构造方法自动注入*******************************/
		// Candidate constructors for autowiring?
		/**
		 * 获取用于实例化的候选构造函数
		 * SmartInstantiationAwareBeanPostProcessor通过这个增强器，可以人为的指定使用的构造方法
		 * @Autowired注解标注在构造方法上，就可以使用该构造方法实例化对象
		 * 该功能就是在此处实现的
		 */
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		/**
		 * ctors != null 说明有@Autowired注解标注的构造方法，此时会执行autowireConstructor方法
		 * 从ctors中再挑出一个合适的构造方法进行实例化
		 */
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			//使用构造器自动注入实例化对象
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		/**
		 * 获取首选的默认构造方法
		 * 对于RootBeanDefinition，该方法返回null
		 * 在实例化前，所有其他的非RootBeanDefinition都会转换成RootBeanDefinition
		 * 所以此处直接忽略
		 */
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			// 构造器注入
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		// 无参构造
		//使用默认的构造方法实例化对象
		return instantiateBean(beanName, mbd);
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 * @param instanceSupplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	//从Supplier获取bean
	protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
		Object instance;

		// 获取原先创建的beanName
		String outerBean = this.currentlyCreatedBean.get();
		// 用当前的替换
		this.currentlyCreatedBean.set(beanName);
		try {
			// 调用Supplier的方法
			instance = instanceSupplier.get();
		}
		finally {
			// 原先的bean存在，设置为原先的
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			}
			// 原先的不存在，就移除，反正原本也为空
			else {
				this.currentlyCreatedBean.remove();
			}
		}

		// 没有创建对象，默认为NullBean
		if (instance == null) {
			instance = new NullBean();
		}
		// 初始化BeanWrapper并返回
		BeanWrapper bw = new BeanWrapperImpl(instance);
		initBeanWrapper(bw);
		return bw;
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// 注册当前创建的bean和给定beanName的依赖
		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}

		// 返回一个bean
		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	//获取用于实例化的候选构造函数
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		//容器中有InstantiationAwareBeanPostProcessor
		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			//遍历所有的BeanPostProcessor
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				/**
				 * 执行SmartInstantiationAwareBeanPostProcessor接口的
				 * determineCandidateConstructors方法，来获取候选的构造方法
				 */
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
					SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
					Constructor<?>[] ctors = ibp.determineCandidateConstructors(beanClass, beanName);
					if (ctors != null) {
						return ctors;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			Object beanInstance;
			//安全检查，忽略
			if (System.getSecurityManager() != null) {
				beanInstance = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(mbd, beanName, this),
						getAccessControlContext());
			}
			/**
			 * 实例化对象
			 * 系统提供的默认实例化策略：private InstantiationStrategy instantiationStrategy =
			 * new CglibSubclassingInstantiationStrategy();
			 */
			else {
				//策略模式完成bean的实例化
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			}
			//使用实例化后的对象构建一个BeanWrapper
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			//初始化BeanWrapper,向里面放一些PropertyEditor对象
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	//使用FactoryMethod实例化一个bean
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	//实例化后封装成BeanWrapper
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

		//使用构造方法解析器来帮助实例化对象
		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param bw the BeanWrapper with bean instance
	 */
	@SuppressWarnings("deprecation")  // for postProcessPropertyValues
	//给bean的属性赋值
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		/************************安全检查*****************************************************/
		if (bw == null) {
			// 如果BeanWrapper为空，但是有属性值，就抛异常
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// Skip property population phase for null instance.
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		/**
		 * 执行InstantiationAwareBeanPostProcessor接口的postProcessAfterInstantiation方法
		 * 返回false表明跳过该bean的属性填充
		 * 此处可以为对象设置属性（相当于对象的默认属性）
		 */
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
					if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
						return;
					}
				}
			}
		}

		/*************************************非注解方式自动注入***********************************************/
		//获取需要填充的属性值（XML配置文件property标签配置的属性或者手动通过api添加（addPropertyValues()方法））
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

		/**
		 * 获取自动注入的模式,这种方式的自动注入已经被注解方法取代了，spring不推荐使用
		 * 有no，byName，byType，AUTOWIRE_CONSTRUCTOR，4种(可以在bean标签中指定autowire="no")
		 * AUTOWIRE_CONSTRUCTOR在实例化bean的时候提到过
		 */
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		//判断是否自动注入
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			// 拷贝配置信息
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			// 根据名称注入
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			// 根据类型注入
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			pvs = newPvs;
		}

		/***********************************注解方式******************************************/
		// 容器是否有InstantiationAwareBeanPostProcessors
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		// 是否进行依赖检查
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		PropertyDescriptor[] filteredPds = null;
		/**
		 * 通过InstantiationAwareBeanPostProcessor的postProcessProperties方法
		 * 和postProcessPropertyValues可以获取需要进行自动注入的属性
		 * spring自动注入@Autowired注解和@Resources注解标注的属性，就是在postProcessProperties完成的
		 * postProcessPropertyValues方法已经过时了，不推荐使用
		 */
		if (hasInstAwareBpps) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			// 后置处理
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				if (bp instanceof InstantiationAwareBeanPostProcessor) {
					InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
					//此方法完成@Autowired和@Value注解属性自动注入
					PropertyValues pvsToUse = ibp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
					if (pvsToUse == null) {
						if (filteredPds == null) {
							filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
						}
						pvsToUse = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
						if (pvsToUse == null) {
							return;
						}
					}
					pvs = pvsToUse;
				}
			}
		}
		/**************************************依赖检查***************************************/
		if (needsDepCheck) {
			if (filteredPds == null) {
				// 过滤出需要依赖检查的属性
				filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			}
			// 检查依赖关系，保证依赖项已初始化
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		//表示 用户可能提供了属性||当前bean需要自动注入（byname，bytype）并且找到了对应正确的属性描述符
		//统一设置属性
		if (pvs != null) {
			// 此时才开始赋值
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	//根据名称注入
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
		// 获取可set的属性，且这个属性不是简单的属性，比如基本类型、包装类这些
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			// 是否有这个bean
			if (containsBean(propertyName)) {
				//根据名字获取到对应的bean对象
				Object bean = getBean(propertyName);
				//添加到属性集合中，等待调用 applyPropertyValues(beanName, mbd, bw, pvs);
				pvs.add(propertyName, bean);
				// 注册依赖关系
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	/**
	 * 1.通过自省得到所有的writeMethod
	 * 2.通过writeMethod的参数获取类型
	 * 3.通过这个类型从容器中查找bean
	 * 4.根据pd属性描述符的名字和bean封装一个pvs
	 */
	//根据类型注入
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		// 类型转换器获取
		//TypeConverter是定义类型转换的接口
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			//BeanWrapper是TypeConverter的子接口
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		// 获取可set的属性，且这个属性不是简单的属性，比如基本类型、包装类这些
		//拿到所有属性描述符的名字
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			try {
				//得到对应属性名字的属性描述
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				// 如果是Object，就不管了,object类型的不能自动注入
				if (Object.class != pd.getPropertyType()) {
					//获取属性set方法对象包装（里面包含方法名）
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					// 是否立即初始化
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					// 依赖描述
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					// 解析依赖，获取依赖对应的对象
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						//加入到MutablePropertyValues中，等待applyPropertyValues(beanName, mbd, bw, pvs);统一赋值
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						// 注册依赖
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	/**
	 * 由此我们可以推断能自动注入的属性必须具备以下几点
	 *
	 * （1）有对应的set方法
	 *
	 * （2）非cglib生成的类的内部属性
	 *
	 * （3）非通过spring中aware接口设置的属性，也就是说你可以注入容器内部属性，但是不能实现这个属性的aware接口
	 *
	 * （4）非配置文件中手动注入的属性（property标签）
	 *
	 * （5）非简单属性
	 * @param mbd
	 * @param bw
	 * @return
	 */
	//返回一个不是简单的bean属性数组不包括基本类型或字符串等简单属性。
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		//得到需要设置值的所有属性的键值对
		PropertyValues pvs = mbd.getPropertyValues();
		/**
		 * 获取当前bean的属性描述(必然包含当前类的class描述，它也是属性)
		 * pd.getWriteMethod() 会获取当前属性set方法对象
		 * pd.getPropertyType() 获取当前属性的类型
		 * isExcludedFromDependencyCheck 当前属性是否被排除（排除cglib生成的类的内部属性，
		 * spring容器的内部属性（实现了spring中的aware接口），这个需要通过BeanPostProcessor接口注入）
		 * !pvs.contains(pd.getName()) 已有的属性集合中不包含（非手动注入过的属性）
		 * !BeanUtils.isSimpleProperty(pd.getPropertyType()) 非简单属性
		 */
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	//过滤出需要依赖检查的属性
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	//指定的bean属性是否被排除在依赖项检查之外。
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	//检查依赖项是否是公开的
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * <p>应用给定的属性值，解决任何在这个bean工厂运行时其他bean的引用。必须使用深拷贝，所以我们
	 * 	 * 不会永久地修改这个属性</p>
	 * 	 * @param beanName the bean name passed for better exception information
	 * 	 *                 -- 传递bean名以获得更好的异常信息
	 * 	 * @param mbd the merged bean definition
	 * 	 *            -- 合并后的bean定义
	 * 	 * @param bw the BeanWrapper wrapping the target object
	 * 	 *           -- 包装目标对象的BeanWrapper
	 * 	 * @param pvs the new property values
	 * 	 *            -- 新的属性值
	 */
	// 将MutablePropertyValues中的属性值填充到实例中
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		//如果pvs没有PropertyValue，直接返回
		if (pvs.isEmpty()) {
			return;
		}

		//如果有安全管理器 且 bw是BeanWrapperImpl的实例
		if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
			//设置bw的安全上下文为工厂的访问控制上下文
			((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
		}

		//MutablePropertyValues：PropertyValues接口的默认实现。允许对属性进行简单操作，
		// 并提供构造函数来支持从映射 进行深度复制和构造
		MutablePropertyValues mpvs = null;
		//原始属性值列表
		List<PropertyValue> original;

		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					// 已完成，直接返回
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			original = mpvs.getPropertyValueList();
		}
		else {
			original = Arrays.asList(pvs.getPropertyValues());
		}

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		// bean定义解析器
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		boolean resolveNecessary = false;
		// 对original的深度复制，已解析的直接加到deepCopy，没解析的先解析，再加入到deepCopy
		for (PropertyValue pv : original) {
			// 已解析
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			else {
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				Object convertedValue = resolvedValue;
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				// 解析值和原值一样
				if (resolvedValue == originalValue) {
					// 避免重复转换
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					deepCopy.add(pv);
				}
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					pv.setConvertedValue(convertedValue);
					deepCopy.add(pv);
				}
				else {
					resolveNecessary = true;
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		if (mpvs != null && !resolveNecessary) {
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	//类型转换
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		/***************************默认的转换器转换**************************************/
		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		/***************************用户自定义的转换器转换*********************************/
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			//得到属性setter方法的参数
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		//安全检查
		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				invokeAwareMethods(beanName, bean);
				return null;
			}, getAccessControlContext());
		}
		else {
			/**
			 * 执行部分aware接口方法，注入容器内部的对象
			 * 先后顺序为BeanNameAware，BeanClassLoaderAware，BeanFactoryAware
			 */
			invokeAwareMethods(beanName, bean);
		}

		Object wrappedBean = bean;
		if (mbd == null || !mbd.isSynthetic()) {
			//执行部分Aware方法 和注解版 lifecycle init callback（比如@PostConstruct）
			//执行所有BeanPostProcessor的postProcessBeforeInitialization方法
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		try {
			// 执行 接口版 lifecycle init callback 比如 InitializingBean的 afterPropertiesSet()
			//还有xml或注解@Bean中的init初始化方法
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}
		if (mbd == null || !mbd.isSynthetic()) {
			//执行所有BeanPostProcessor的postProcessAfterInitialization方法
			/**
			 * 完成aop---生成代理
			 * 事件发布 监听
			 */
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		return wrappedBean;
	}

	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			if (bean instanceof BeanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	/**
	 * spring的三种初始化的方式
	 *
	 * （1）xml中init-method="xxx" 、@Bean注解中指定的初始化方法
	 *
	 * （2）注解@PostConstruct，该注解由BeanPostProcessor的postProcessBeforeInitialization方法处理
	 *
	 * （3）实现InitializingBean接口
	 * @param beanName
	 * @param bean
	 * @param mbd
	 * @throws Throwable
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		boolean isInitializingBean = (bean instanceof InitializingBean);
		// 如果是InitializingBean并且有afterPropertiesSet，调用afterPropertiesSet方法
		if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						((InitializingBean) bean).afterPropertiesSet();
						return null;
					}, getAccessControlContext());
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		// 如果是InitializingBean，但没有afterPropertiesSet，调用自定义的方法
		if (mbd != null && bean.getClass() != NullBean.class) {
			String initMethodName = mbd.getInitMethodName();
			if (StringUtils.hasLength(initMethodName) &&
					!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
					!mbd.isExternallyManagedInitMethod(initMethodName)) {
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd)
			throws Throwable {

		// 获取初始化方法名称
		String initMethodName = mbd.getInitMethodName();
		Assert.state(initMethodName != null, "No init method set");
		// 获取初始化方法
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod);

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				ReflectionUtils.makeAccessible(methodToInvoke);
				return null;
			});
			try {
				AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
						() -> methodToInvoke.invoke(bean), getAccessControlContext());
			}
			catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		}
		else {
			try {
				// 给权限
				ReflectionUtils.makeAccessible(methodToInvoke);
				//反射调用
				methodToInvoke.invoke(bean);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	//调用后置处理器
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	//移除单例，这边额外多了factoryBeanInstanceCache缓存
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	//清空单例，这边额外多了factoryBeanInstanceCache缓存
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				}
				else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
