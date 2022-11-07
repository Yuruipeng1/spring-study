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

package org.springframework.context.annotation;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utilities for identifying {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	public static final String CONFIGURATION_CLASS_FULL = "full";

	public static final String CONFIGURATION_CLASS_LITE = "lite";

	public static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	//检查beanDef是否是一个配置类
	//只要类中有这5个注解（@Component，@ComponentScan，@ImportResource，@Import，@Configuration、@Bean）之一，
	// 就表明该类是一个配置类。
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

		//bean的完全限定名
		String className = beanDef.getBeanClassName();
		//工厂类肯定不是配置类
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		/**
		 * spring中component-scan扫描@Component、@Configuration等类
		 * 形成ScannedGenericBeanDefinition,实现了AnnotatedBeanDefinition接口，
		 * 代表注解配置的bean
		 * 而xml配置文件配置的普通的bean标签形成的是GenericBeanDefinition
		 */
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			/**
			 * className.equals(((AnnotatedBeanDefinition)beanDef).getMetadata().getClassName())
			 * 判定成功表明此时BeanDefinition中的metadata和BeanDefinition是匹配的，可以重用
			 *
			 * ScannedGenericBeanDefinition的metadata类型为SimpleAnnotationMetadata
			 */
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		/**
		 * 判断这个BeanDefinition对应的类是否已经被加载到jvm
		 * hasBeanClass()主要就是这个方法，
		 * 它判断BeanDefinition中beanClass属性是类名(未加载到jvm)还是clazz对象(已加载到jvm)
		 */
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			/**
			 * BeanFactoryPostProcessor
			 * BeanPostProcessor
			 * AopInfrastructureBean
			 * EventListenerFactory
			 * 这四种类型的bean也不是配置类
			 */
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			//创建StandardAnnotationMetadata，实际上就是使用简单反射来获取AnnotationMetadata
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		else {
			try {
				/**
				 * 使用metadataReaderFactory获取对应类的MetadataReader
				 * 再通过MetadataReader得到AnnotationMetadata
				 *
				 * 通过ASM来获取AnnotationMetadata
				 */
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}

		//获取@Configuration注解的所有属性
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		/**
		 * proxyBeanMethods是@Configuration注解的一个属性
		 * 默认值为true，表示创建当前类的代理类，这样做的好处就是：可以拦截@Bean注解的方法，
		 * 当你在当前类中使用@Bean注解的方法获取对象的时候，不管调用多少次，返回的都是同一个对象
		 */
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			//将配置类标识设置为full,全配置类
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		/**
		 * 检测当前类中是否包含其他配置注解
		 * Component
		 * ComponentScan
		 * Import
		 * ImportResource
		 * Bean
		 */
		else if (config != null || isConfigurationCandidate(metadata)) {
			//将配置类标识设置为lite，半配置类
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		else {
			//非配置类
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		/**
		 * 获取当前类中@Order注解的信息
		 * 主要是为了获取配置类的执行顺序
		 * 也是设置到BeanDefinition中
		 */
		Integer order = getOrder(metadata);
		if (order != null) {
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}

		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	//检测当前类中是否包含其他配置注解
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		//当前类肯定不能是一个接口或注解
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		/**
		 * candidateIndicators.add(Component.class.getName());
		 * candidateIndicators.add(ComponentScan.class.getName());
		 * candidateIndicators.add(Import.class.getName());
		 * candidateIndicators.add(ImportResource.class.getName());
		 * 有这4个注解，表明是一个lite配置类
		 */
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		return hasBeanMethods(metadata);
	}

	static boolean hasBeanMethods(AnnotationMetadata metadata) {
		try {
			//判断当前类中包不包含有@Bean注解的方法
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		//获取@Order注解的信息
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
