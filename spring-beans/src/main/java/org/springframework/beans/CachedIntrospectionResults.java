/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.beans;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.SpringProperties;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * Internal class that caches JavaBeans {@link java.beans.PropertyDescriptor}
 * information for a Java class. Not intended for direct use by application code.
 *
 * <p>Necessary for Spring's own caching of bean descriptors within the application
 * {@link ClassLoader}, rather than relying on the JDK's system-wide {@link BeanInfo}
 * cache (in order to avoid leaks on individual application shutdown in a shared JVM).
 *
 * <p>Information is cached statically, so we don't need to create new
 * objects of this class for every JavaBean we manipulate. Hence, this class
 * implements the factory design pattern, using a private constructor and
 * a static {@link #forClass(Class)} factory method to obtain instances.
 *
 * <p>Note that for caching to work effectively, some preconditions need to be met:
 * Prefer an arrangement where the Spring jars live in the same ClassLoader as the
 * application classes, which allows for clean caching along with the application's
 * lifecycle in any case. For a web application, consider declaring a local
 * {@link org.springframework.web.util.IntrospectorCleanupListener} in {@code web.xml}
 * in case of a multi-ClassLoader layout, which will allow for effective caching as well.
 *
 * <p>In case of a non-clean ClassLoader arrangement without a cleanup listener having
 * been set up, this class will fall back to a weak-reference-based caching model that
 * recreates much-requested entries every time the garbage collector removed them. In
 * such a scenario, consider the {@link #IGNORE_BEANINFO_PROPERTY_NAME} system property.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 05 May 2001
 * @see #acceptClassLoader(ClassLoader)
 * @see #clearClassLoader(ClassLoader)
 * @see #forClass(Class)
 */

/**
 * 内省是java提供的一种快捷访问javaBean这种类型类的方式，本质上还是反射。
 * 通过内省，可以快速获得getter，setter方法和它们对应的属性。通过内省获取某个类的属性必然包含class属性，
 * 因为任何类的对象都有getClass方法。
 *
 * Field和Property的区别
 *
 * Property是相对于getter、setter方法来说的，一个getter或setter方法就代表了一个Property。
 * 例如：我们可以通过user.getClass()方法得到user对象的clazz对象，那么User类和它的父类中真的定义了Class class=User.class吗？
 * 肯定没有啊，但是我们却可以通过getClass()方法得到clazz对象，也就是说并没有为User类分配了栈内存存储clazz对象的引用。
 *
 * Field则是JDK反射定义的，用来代表类中分配了栈内存的字段，即明确在类中声明了Role role;，这种就是Field
 */
public final class CachedIntrospectionResults {

	/**
	 * System property that instructs Spring to use the {@link Introspector#IGNORE_ALL_BEANINFO}
	 * mode when calling the JavaBeans {@link Introspector}: "spring.beaninfo.ignore", with a
	 * value of "true" skipping the search for {@code BeanInfo} classes (typically for scenarios
	 * where no such classes are being defined for beans in the application in the first place).
	 * <p>The default is "false", considering all {@code BeanInfo} metadata classes, like for
	 * standard {@link Introspector#getBeanInfo(Class)} calls. Consider switching this flag to
	 * "true" if you experience repeated ClassLoader access for non-existing {@code BeanInfo}
	 * classes, in case such access is expensive on startup or on lazy loading.
	 * <p>Note that such an effect may also indicate a scenario where caching doesn't work
	 * effectively: Prefer an arrangement where the Spring jars live in the same ClassLoader
	 * as the application classes, which allows for clean caching along with the application's
	 * lifecycle in any case. For a web application, consider declaring a local
	 * {@link org.springframework.web.util.IntrospectorCleanupListener} in {@code web.xml}
	 * in case of a multi-ClassLoader layout, which will allow for effective caching as well.
	 * @see Introspector#getBeanInfo(Class, int)
	 */
	public static final String IGNORE_BEANINFO_PROPERTY_NAME = "spring.beaninfo.ignore";

	//空的属性描述数组，如果某个类没有getter、setter方法，就会使用它
	private static final PropertyDescriptor[] EMPTY_PROPERTY_DESCRIPTOR_ARRAY = {};


	//得到根路径下spring.properties文件中spring.beaninfo.ignore的值
	private static final boolean shouldIntrospectorIgnoreBeaninfoClasses =
			SpringProperties.getFlag(IGNORE_BEANINFO_PROPERTY_NAME);

	/** Stores the BeanInfoFactory instances.
	 * 通过某种算法逻辑得到所有的BeanInfoFactory对象
	 *  */
	private static final List<BeanInfoFactory> beanInfoFactories = SpringFactoriesLoader.loadFactories(
			BeanInfoFactory.class, CachedIntrospectionResults.class.getClassLoader());

	private static final Log logger = LogFactory.getLog(CachedIntrospectionResults.class);

	/**
	 * Set of ClassLoaders that this CachedIntrospectionResults class will always
	 * accept classes from, even if the classes do not qualify as cache-safe.
	 */
	//可使用的类加载器
	static final Set<ClassLoader> acceptedClassLoaders =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * Map keyed by Class containing CachedIntrospectionResults, strongly held.
	 * This variant is being used for cache-safe bean classes.
	 */
	//内省结果缓存
	static final ConcurrentMap<Class<?>, CachedIntrospectionResults> strongClassCache =
			new ConcurrentHashMap<>(64);

	/**
	 * Map keyed by Class containing CachedIntrospectionResults, softly held.
	 * This variant is being used for non-cache-safe bean classes.
	 */
	//包含类内省结果缓存
	static final ConcurrentMap<Class<?>, CachedIntrospectionResults> softClassCache =
			new ConcurrentReferenceHashMap<>(64);


	/**
	 * Accept the given ClassLoader as cache-safe, even if its classes would
	 * not qualify as cache-safe in this CachedIntrospectionResults class.
	 * <p>This configuration method is only relevant in scenarios where the Spring
	 * classes reside in a 'common' ClassLoader (e.g. the system ClassLoader)
	 * whose lifecycle is not coupled to the application. In such a scenario,
	 * CachedIntrospectionResults would by default not cache any of the application's
	 * classes, since they would create a leak in the common ClassLoader.
	 * <p>Any {@code acceptClassLoader} call at application startup should
	 * be paired with a {@link #clearClassLoader} call at application shutdown.
	 * @param classLoader the ClassLoader to accept
	 */
	public static void acceptClassLoader(@Nullable ClassLoader classLoader) {
		if (classLoader != null) {
			acceptedClassLoaders.add(classLoader);
		}
	}

	/**
	 * Clear the introspection cache for the given ClassLoader, removing the
	 * introspection results for all classes underneath that ClassLoader, and
	 * removing the ClassLoader (and its children) from the acceptance list.
	 * @param classLoader the ClassLoader to clear the cache for
	 */
	public static void clearClassLoader(@Nullable ClassLoader classLoader) {
		acceptedClassLoaders.removeIf(registeredLoader ->
				isUnderneathClassLoader(registeredLoader, classLoader));
		strongClassCache.keySet().removeIf(beanClass ->
				isUnderneathClassLoader(beanClass.getClassLoader(), classLoader));
		softClassCache.keySet().removeIf(beanClass ->
				isUnderneathClassLoader(beanClass.getClassLoader(), classLoader));
	}

	/**
	 * Create CachedIntrospectionResults for the given bean class.
	 * @param beanClass the bean class to analyze
	 * @return the corresponding CachedIntrospectionResults
	 * @throws BeansException in case of introspection failure
	 */
	//得到beanClass类的内省结果
	static CachedIntrospectionResults forClass(Class<?> beanClass) throws BeansException {
		//先从缓存中获取
		CachedIntrospectionResults results = strongClassCache.get(beanClass);
		if (results != null) {
			return results;
		}
		results = softClassCache.get(beanClass);
		if (results != null) {
			return results;
		}

		//创建一个beanClass的可缓存的内省结果
		results = new CachedIntrospectionResults(beanClass);
		ConcurrentMap<Class<?>, CachedIntrospectionResults> classCacheToUse;

		//使用当前类加载器加载的是缓存安全的
		if (ClassUtils.isCacheSafe(beanClass, CachedIntrospectionResults.class.getClassLoader()) ||
				isClassLoaderAccepted(beanClass.getClassLoader())) {
			classCacheToUse = strongClassCache;
		}
		//不安全
		else {
			if (logger.isDebugEnabled()) {
				logger.debug("Not strongly caching class [" + beanClass.getName() + "] because it is not cache-safe");
			}
			classCacheToUse = softClassCache;
		}

		//缓存
		CachedIntrospectionResults existing = classCacheToUse.putIfAbsent(beanClass, results);
		return (existing != null ? existing : results);
	}

	/**
	 * Check whether this CachedIntrospectionResults class is configured
	 * to accept the given ClassLoader.
	 * @param classLoader the ClassLoader to check
	 * @return whether the given ClassLoader is accepted
	 * @see #acceptClassLoader
	 */
	private static boolean isClassLoaderAccepted(ClassLoader classLoader) {
		for (ClassLoader acceptedLoader : acceptedClassLoaders) {
			if (isUnderneathClassLoader(classLoader, acceptedLoader)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check whether the given ClassLoader is underneath the given parent,
	 * that is, whether the parent is within the candidate's hierarchy.
	 * @param candidate the candidate ClassLoader to check
	 * @param parent the parent ClassLoader to check for
	 */
	private static boolean isUnderneathClassLoader(@Nullable ClassLoader candidate, @Nullable ClassLoader parent) {
		if (candidate == parent) {
			return true;
		}
		if (candidate == null) {
			return false;
		}
		ClassLoader classLoaderToCheck = candidate;
		while (classLoaderToCheck != null) {
			classLoaderToCheck = classLoaderToCheck.getParent();
			if (classLoaderToCheck == parent) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Retrieve a {@link BeanInfo} descriptor for the given target class.
	 * @param beanClass the target class to introspect
	 * @return the resulting {@code BeanInfo} descriptor (never {@code null})
	 * @throws IntrospectionException from the underlying {@link Introspector}
	 */
	private static BeanInfo getBeanInfo(Class<?> beanClass) throws IntrospectionException {
		/**
		 * spring对原生的内省包装了一下
		 * 返回的BeanInfo类型是spring自己定义的ExtendedBeanInfo，实现更强大的功能，获取更多的信息
		 */
		for (BeanInfoFactory beanInfoFactory : beanInfoFactories) {
			BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanClass);
			if (beanInfo != null) {
				return beanInfo;
			}
		}
		//原生，使用的JDK中的Introspector工具类实现内省，返回SimpleBeanInfo，里面方法很少
		return (shouldIntrospectorIgnoreBeaninfoClasses ?
				Introspector.getBeanInfo(beanClass, Introspector.IGNORE_ALL_BEANINFO) :
				Introspector.getBeanInfo(beanClass));
	}


	/** The BeanInfo object for the introspected bean class. */
	//内省结果得到的bean信息
	private final BeanInfo beanInfo;

	/** PropertyDescriptor objects keyed by property name String. */
	/**
	 * 某个类的所有属性描述
	 * 内省就是为了得到它
	 */
	private final Map<String, PropertyDescriptor> propertyDescriptors;

	/** TypeDescriptor objects keyed by PropertyDescriptor. */
	//属性描述对应的类型描述
	private final ConcurrentMap<PropertyDescriptor, TypeDescriptor> typeDescriptorCache;


	/**
	 * Create a new CachedIntrospectionResults instance for the given class.
	 * @param beanClass the bean class to analyze
	 * @throws BeansException in case of introspection failure
	 */
	private CachedIntrospectionResults(Class<?> beanClass) throws BeansException {
		try {
			if (logger.isTraceEnabled()) {
				logger.trace("Getting BeanInfo for class [" + beanClass.getName() + "]");
			}
			//内省获取BeanInfo
			this.beanInfo = getBeanInfo(beanClass);

			if (logger.isTraceEnabled()) {
				logger.trace("Caching PropertyDescriptors for class [" + beanClass.getName() + "]");
			}
			this.propertyDescriptors = new LinkedHashMap<>();

			// This call is slow so we do it once.
			//获取beanClass中所有PropertyDescriptor（原生或spring增强的）
			PropertyDescriptor[] pds = this.beanInfo.getPropertyDescriptors();
			for (PropertyDescriptor pd : pds) {
				if (Class.class == beanClass && !("name".equals(pd.getName()) ||
						(pd.getName().endsWith("Name") && String.class == pd.getPropertyType()))) {
					// Only allow all name variants of Class properties
					continue;
				}
				if (URL.class == beanClass && "content".equals(pd.getName())) {
					// Only allow URL attribute introspection, not content resolution
					continue;
				}
				if (pd.getWriteMethod() == null && isInvalidReadOnlyPropertyType(pd.getPropertyType())) {
					// Ignore read-only properties such as ClassLoader - no need to bind to those
					continue;
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Found bean property '" + pd.getName() + "'" +
							(pd.getPropertyType() != null ? " of type [" + pd.getPropertyType().getName() + "]" : "") +
							(pd.getPropertyEditorClass() != null ?
									"; editor [" + pd.getPropertyEditorClass().getName() + "]" : ""));
				}
				/**
				 * 增强PropertyDescriptor
				 * GenericTypeAwarePropertyDescriptor是spring定义，继承了PropertyDescriptor
				 * 添加了更多的方法，方便获取属性相关的信息
				 */
				pd = buildGenericTypeAwarePropertyDescriptor(beanClass, pd);
				//缓存起来
				this.propertyDescriptors.put(pd.getName(), pd);
			}

			// Explicitly check implemented interfaces for setter/getter methods as well,
			// in particular for Java 8 default methods...
			Class<?> currClass = beanClass;
			while (currClass != null && currClass != Object.class) {
				//内省处理接口有默认实现的setter/getter方法
				introspectInterfaces(beanClass, currClass);
				currClass = currClass.getSuperclass();
			}

			this.typeDescriptorCache = new ConcurrentReferenceHashMap<>();
		}
		catch (IntrospectionException ex) {
			throw new FatalBeanException("Failed to obtain BeanInfo for class [" + beanClass.getName() + "]", ex);
		}
	}

	//内省处理接口有默认实现的setter/getter方法
	private void introspectInterfaces(Class<?> beanClass, Class<?> currClass) throws IntrospectionException {
		//遍历当前类的所有接口
		for (Class<?> ifc : currClass.getInterfaces()) {
			/**
			 * isJavaLanguageInterface方法判断是否是这几个Serializable、Externalizable、
			 * Closeable、AutoCloseable、Cloneable、Comparable接口
			 */
			if (!ClassUtils.isJavaLanguageInterface(ifc)) {
				//内省获取接口的PropertyDescriptor
				for (PropertyDescriptor pd : getBeanInfo(ifc).getPropertyDescriptors()) {
					/**
					 * 从子类的PropertyDescriptor集合中查找
					 * 判断子类是否重写了接口有默认实现的方法
					 */
					PropertyDescriptor existingPd = this.propertyDescriptors.get(pd.getName());
					if (existingPd == null ||
							(existingPd.getReadMethod() == null && pd.getReadMethod() != null)) {
						// GenericTypeAwarePropertyDescriptor leniently resolves a set* write method
						// against a declared read method, so we prefer read method descriptors here.
						//增强PropertyDescriptor
						pd = buildGenericTypeAwarePropertyDescriptor(beanClass, pd);
						if (pd.getWriteMethod() == null && isInvalidReadOnlyPropertyType(pd.getPropertyType())) {
							// Ignore read-only properties such as ClassLoader - no need to bind to those
							continue;
						}
						//缓存起来
						this.propertyDescriptors.put(pd.getName(), pd);
					}
				}
				//递归
				introspectInterfaces(ifc, ifc);
			}
		}
	}

	private boolean isInvalidReadOnlyPropertyType(@Nullable Class<?> returnType) {
		return (returnType != null && (AutoCloseable.class.isAssignableFrom(returnType) ||
				ClassLoader.class.isAssignableFrom(returnType) ||
				ProtectionDomain.class.isAssignableFrom(returnType)));
	}


	BeanInfo getBeanInfo() {
		return this.beanInfo;
	}

	Class<?> getBeanClass() {
		return this.beanInfo.getBeanDescriptor().getBeanClass();
	}

	@Nullable
	//获取对应属性名的属性描述
	PropertyDescriptor getPropertyDescriptor(String name) {
		//通过名字得到对应的属性描述
		PropertyDescriptor pd = this.propertyDescriptors.get(name);
		if (pd == null && StringUtils.hasLength(name)) {
			// Same lenient fallback checking as in Property...
			pd = this.propertyDescriptors.get(StringUtils.uncapitalize(name));
			if (pd == null) {
				pd = this.propertyDescriptors.get(StringUtils.capitalize(name));
			}
		}
		return pd;
	}

	PropertyDescriptor[] getPropertyDescriptors() {
		return this.propertyDescriptors.values().toArray(EMPTY_PROPERTY_DESCRIPTOR_ARRAY);
	}

	private PropertyDescriptor buildGenericTypeAwarePropertyDescriptor(Class<?> beanClass, PropertyDescriptor pd) {
		try {
			return new GenericTypeAwarePropertyDescriptor(beanClass, pd.getName(), pd.getReadMethod(),
					pd.getWriteMethod(), pd.getPropertyEditorClass());
		}
		catch (IntrospectionException ex) {
			throw new FatalBeanException("Failed to re-introspect class [" + beanClass.getName() + "]", ex);
		}
	}

	TypeDescriptor addTypeDescriptor(PropertyDescriptor pd, TypeDescriptor td) {
		TypeDescriptor existing = this.typeDescriptorCache.putIfAbsent(pd, td);
		return (existing != null ? existing : td);
	}

	@Nullable
	TypeDescriptor getTypeDescriptor(PropertyDescriptor pd) {
		return this.typeDescriptorCache.get(pd);
	}

}
