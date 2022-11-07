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

package org.springframework.context;

import java.io.Closeable;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ProtocolResolver;
import org.springframework.lang.Nullable;

/**
 * SPI interface to be implemented by most if not all application contexts.
 * Provides facilities to configure an application context in addition
 * to the application context client methods in the
 * {@link org.springframework.context.ApplicationContext} interface.
 *
 * <p>Configuration and lifecycle methods are encapsulated here to avoid
 * making them obvious to ApplicationContext client code. The present
 * methods should only be used by startup and shutdown code.
 *  * ~~~~~~~~~~~~~~~~~~~~~~~~
 *  * 中文解释：
 *  * SPI接口将由大多数（而不是全部）应用程序上下文实现。
 *  * 除了ApplicationContext接口中的ApplicationContext程序上下文客户端方法外，
 *  * 还提供了配置应用程序上下文的功能。
 *  * 配置和生命周期方法在此处进行了封装，以避免对ApplicationContext客户端代码显而易见。
 *  * 本方法仅应由启动和关闭代码使用
 *  * ~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @since 03.11.2003
 */
//该接口的主要任务就是配置应用程序上下文功能。
public interface ConfigurableApplicationContext extends ApplicationContext, Lifecycle, Closeable {

	/**
	 * Any number of these characters are considered delimiters between
	 * multiple context config paths in a single String value.
	 * 在单个String值中，可以将任意数量的这些字符视为多个上下文配置路径之间的分隔符。
	 * 	 * 就是设置多个应用上下文配置文件的的分隔符
	 * @see org.springframework.context.support.AbstractXmlApplicationContext#setConfigLocation
	 * @see org.springframework.web.context.ContextLoader#CONFIG_LOCATION_PARAM
	 * @see org.springframework.web.servlet.FrameworkServlet#setContextConfigLocation
	 */
	String CONFIG_LOCATION_DELIMITERS = ",; \t\n";

	/**
	 * Name of the ConversionService bean in the factory.
	 * If none is supplied, default conversion rules apply.
	 * @since 3.0
	 * @see org.springframework.core.convert.ConversionService
	 */
	String CONVERSION_SERVICE_BEAN_NAME = "conversionService";

	/**
	 * Name of the LoadTimeWeaver bean in the factory. If such a bean is supplied,
	 * the context will use a temporary ClassLoader for type matching, in order
	 * to allow the LoadTimeWeaver to process all actual bean classes.
	 * @since 2.5
	 * @see org.springframework.instrument.classloading.LoadTimeWeaver
	 */
	String LOAD_TIME_WEAVER_BEAN_NAME = "loadTimeWeaver";

	/**
	 * Name of the {@link Environment} bean in the factory.
	 * @since 3.1
	 */
	String ENVIRONMENT_BEAN_NAME = "environment";

	/**
	 * Name of the System properties bean in the factory.
	 * @see java.lang.System#getProperties()
	 */
	String SYSTEM_PROPERTIES_BEAN_NAME = "systemProperties";

	/**
	 * Name of the System environment bean in the factory.
	 * @see java.lang.System#getenv()
	 */
	String SYSTEM_ENVIRONMENT_BEAN_NAME = "systemEnvironment";

	/**
	 * {@link Thread#getName() Name} of the {@linkplain #registerShutdownHook()
	 * shutdown hook} thread: {@value}.
	 * @since 5.2
	 * @see #registerShutdownHook()
	 */
	String SHUTDOWN_HOOK_THREAD_NAME = "SpringContextShutdownHook";


	/**
	 * 给应用上下文-容器设置唯一的id
	 * Set the unique id of this application context.
	 * @since 3.0
	 */
	void setId(String id);

	/**
	 * Set the parent of this application context.
	 * <p>Note that the parent shouldn't be changed: It should only be set outside
	 * a constructor if it isn't available when an object of this class is created,
	 * for example in case of WebApplicationContext setup.
	 *  * 为该应用上下文设置父级
	 * 	 * 请注意，不应更改父级：仅当在创建此类的对象时不可用
	 * 	 * （例如在WebApplicationContext安装的情况下）时，才应在构造函数外部设置父级。
	 * @param parent the parent context
	 * @see org.springframework.web.context.ConfigurableWebApplicationContext
	 */
	void setParent(@Nullable ApplicationContext parent);

	/**
	 * Set the {@code Environment} for this application context.
	 * 为该应用上下文设置环境变量
	 * @param environment the new environment
	 * @since 3.1
	 */
	void setEnvironment(ConfigurableEnvironment environment);

	/**
	 * Return the {@code Environment} for this application context in configurable
	 *  以可配置的形式返回此应用程序上下文的{@code Environment}，以便进行进一步的自定义
	 * form, allowing for further customization.
	 * @since 3.1
	 */
	@Override
	ConfigurableEnvironment getEnvironment();

	/**
	 * Add a new BeanFactoryPostProcessor that will get applied to the internal
	 * bean factory of this application context on refresh, before any of the
	 * bean definitions get evaluated. To be invoked during context configuration.
	 * @param postProcessor the factory processor to register
	 */
	void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);

	/**
	 * Add a new ApplicationListener that will be notified on context events
	 * such as context refresh and context shutdown.
	 * <p>Note that any ApplicationListener registered here will be applied
	 * on refresh if the context is not active yet, or on the fly with the
	 * current event multicaster in case of a context that is already active.
	 *
	 * 添加一个在应用监听器，他将在应用事件被发现，列如应用事件：刷新和关闭应用上下文
	 *
	 * @param listener the ApplicationListener to register
	 * @see org.springframework.context.event.ContextRefreshedEvent
	 * @see org.springframework.context.event.ContextClosedEvent
	 */
	void addApplicationListener(ApplicationListener<?> listener);

	/**
	 * Specify the ClassLoader to load class path resources and bean classes with.
	 * <p>This context class loader will be passed to the internal bean factory.
	 * @since 5.2.7
	 *
	 *  * 指定ClassLoader以加载类路径资源和Bean类。
	 * 	 * 该上下文类加载器将传递给内部bean工厂
	 *
	 * @see org.springframework.core.io.DefaultResourceLoader#DefaultResourceLoader(ClassLoader)
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#setBeanClassLoader
	 */
	void setClassLoader(ClassLoader classLoader);

	/**
	 * Register the given protocol resolver with this application context,
	 * allowing for additional resource protocols to be handled.
	 * <p>Any such resolver will be invoked ahead of this context's standard
	 * resolution rules. It may therefore also override any default rules.
	 *  * ~~~~~~~~~~~~~~~~~~~
	 * 	 * 想该应用上下文注册给定的协议解析器
	 * 	 * 允许处理其他协议资源
	 * 	 * 任何此类解析程序都将在此上下文的标准解析规则之前调用。
	 * 	 * 因此，它也可以覆盖任何默认规则。
	 * 	 * ~~~~~~~~~~~~~~~~~~~
	 * @since 4.3
	 */
	void addProtocolResolver(ProtocolResolver resolver);

	/**
	 * Load or refresh the persistent representation of the configuration, which
	 * might be from Java-based configuration, an XML file, a properties file, a
	 * relational database schema, or some other format.
	 *  * 加载或刷新配置的持久表示形式，
	 * 	 * 该表示形式可能来自基于Java的配置，XML文件，属性文件，关系数据库模式或其他某种格式。
	 * <p>As this is a startup method, it should destroy already created singletons
	 * if it fails, to avoid dangling resources. In other words, after invocation
	 * of this method, either all or no singletons at all should be instantiated.
	 *
	 *  * 因为这是一个启动方法（在后面的创建容器方法，该方法当做容器创建方法），如果失败了，它应该销毁样创
	 * 	 * 的单例，换句话说，在调用此方法之后，应实例化所有单例或根本不实例化。
	 *
	 * @throws BeansException if the bean factory could not be initialized
	 * @throws IllegalStateException if already initialized and multiple refresh
	 * attempts are not supported
	 */
	void refresh() throws BeansException, IllegalStateException;

	/**
	 * Register a shutdown hook with the JVM runtime, closing this context
	 * on JVM shutdown unless it has already been closed at that time.
	 * <p>This method can be called multiple times. Only one shutdown hook
	 * (at max) will be registered for each context instance.
	 * <p>As of Spring Framework 5.2, the {@linkplain Thread#getName() name} of
	 * the shutdown hook thread should be {@link #SHUTDOWN_HOOK_THREAD_NAME}.
	 * @see java.lang.Runtime#addShutdownHook
	 * @see #close()
	 */
	//注册关闭挂钩
	void registerShutdownHook();

	/**
	 * Close this application context, releasing all resources and locks that the
	 * implementation might hold. This includes destroying all cached singleton beans.
	 * <p>Note: Does <i>not</i> invoke {@code close} on a parent context;
	 * parent contexts have their own, independent lifecycle.
	 *
	 * 	 * 关闭此应用程序上下文，释放实现可能持有的所有资源和锁。
	 * 	 * 这包括销毁所有缓存的单例bean。
	 * 	 * 注意：不会在父上下文上调用close ； 父级上下文具有自己的独立生命周期。
	 *
	 * <p>This method can be called multiple times without side effects: Subsequent
	 * {@code close} calls on an already closed context will be ignored.
	 *
	 * * 这个方法可以多次调用此方法而没有副作用：在已经关闭的上下文上进行的后续close调用将被忽略。
	 */
	@Override
	void close();

	/**
	 * Determine whether this application context is active, that is,
	 * whether it has been refreshed at least once and has not been closed yet.
	 *
	 * * 确定应用上下文是否处于活动状态，，即，是否至少刷新一次并且尚未关闭。
	 *
	 * @return whether the context is still active
	 * @see #refresh()
	 * @see #close()
	 * @see #getBeanFactory()
	 */
	boolean isActive();

	/**
	 * Return the internal bean factory of this application context.
	 * Can be used to access specific functionality of the underlying factory.
	 * <p>Note: Do not use this to post-process the bean factory; singletons
	 * will already have been instantiated before. Use a BeanFactoryPostProcessor
	 * to intercept the BeanFactory setup process before beans get touched.
	 * <p>Generally, this internal factory will only be accessible while the context
	 * is active, that is, in-between {@link #refresh()} and {@link #close()}.
	 * The {@link #isActive()} flag can be used to check whether the context
	 * is in an appropriate state.
	 *
	 *  * ~~~~~~~~~~~~~~~~
	 * 	 * 返回该应用上下文的内部bean工厂
	 * 	 * ~~~~~~~~~~~~~~~~
	 *
	 * @return the underlying bean factory
	 * @throws IllegalStateException if the context does not hold an internal
	 * bean factory (usually if {@link #refresh()} hasn't been called yet or
	 * if {@link #close()} has already been called)
	 * @see #isActive()
	 * @see #refresh()
	 * @see #close()
	 * @see #addBeanFactoryPostProcessor
	 */
	ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;

}
