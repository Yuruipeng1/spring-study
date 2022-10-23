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

package org.springframework.core.env;

import java.util.Map;

/**
 * Configuration interface to be implemented by most if not all {@link Environment} types.
 * Provides facilities for setting active and default profiles and manipulating underlying
 * property sources. Allows clients to set and validate required properties, customize the
 * conversion service and more through the {@link ConfigurablePropertyResolver}
 * superinterface.
 *
 * <h2>Manipulating property sources</h2>
 * <p>Property sources may be removed, reordered, or replaced; and additional
 * property sources may be added using the {@link MutablePropertySources}
 * instance returned from {@link #getPropertySources()}. The following examples
 * are against the {@link StandardEnvironment} implementation of
 * {@code ConfigurableEnvironment}, but are generally applicable to any implementation,
 * though particular default property sources may differ.
 *
 * <h4>Example: adding a new property source with highest search priority</h4>
 * <pre class="code">
 * ConfigurableEnvironment environment = new StandardEnvironment();
 * MutablePropertySources propertySources = environment.getPropertySources();
 * Map&lt;String, String&gt; myMap = new HashMap&lt;&gt;();
 * myMap.put("xyz", "myValue");
 * propertySources.addFirst(new MapPropertySource("MY_MAP", myMap));
 * </pre>
 *
 * <h4>Example: removing the default system properties property source</h4>
 * <pre class="code">
 * MutablePropertySources propertySources = environment.getPropertySources();
 * propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
 * </pre>
 *
 * <h4>Example: mocking the system environment for testing purposes</h4>
 * <pre class="code">
 * MutablePropertySources propertySources = environment.getPropertySources();
 * MockPropertySource mockEnvVars = new MockPropertySource().withProperty("xyz", "myValue");
 * propertySources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mockEnvVars);
 * </pre>
 *
 * When an {@link Environment} is being used by an {@code ApplicationContext}, it is
 * important that any such {@code PropertySource} manipulations be performed
 * <em>before</em> the context's {@link
 * org.springframework.context.support.AbstractApplicationContext#refresh() refresh()}
 * method is called. This ensures that all property sources are available during the
 * container bootstrap process, including use by {@linkplain
 * org.springframework.context.support.PropertySourcesPlaceholderConfigurer property
 * placeholder configurers}.
 *
 * @author Chris Beams
 * @since 3.1
 * @see StandardEnvironment
 * @see org.springframework.context.ConfigurableApplicationContext#getEnvironment
 */
public interface ConfigurableEnvironment extends Environment, ConfigurablePropertyResolver {

	/**
	 * Specify the set of profiles active for this {@code Environment}. Profiles are
	 * evaluated during container bootstrap to determine whether bean definitions
	 * should be registered with the container.
	 * <p>Any existing active profiles will be replaced with the given arguments; call
	 * with zero arguments to clear the current set of active profiles. Use
	 * {@link #addActiveProfile} to add a profile while preserving the existing set.
	 *
	 * * ~~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 中文解释
	 * 	 * 指定为此Environment激活的配置文件集。
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~~
	 * @throws IllegalArgumentException if any profile is null, empty or whitespace-only
	 * @see #addActiveProfile
	 * @see #setDefaultProfiles
	 * @see org.springframework.context.annotation.Profile
	 * @see AbstractEnvironment#ACTIVE_PROFILES_PROPERTY_NAME
	 */
	void setActiveProfiles(String... profiles);

	/**
	 * Add a profile to the current set of active profiles.
	 * * ~~~~~~~~~~~~~~~~
	 * 	 * 中文解释：
	 * 	 * 在当前激活文件集中增加配置文件
	 * 	 * ~~~~~~~~~~~~~~~~
	 * @throws IllegalArgumentException if the profile is null, empty or whitespace-only
	 * @see #setActiveProfiles
	 */
	void addActiveProfile(String profile);

	/**
	 * Specify the set of profiles to be made active by default if no other profiles
	 * are explicitly made active through {@link #setActiveProfiles}.
	 *  * ~~~~~~~~~~~~~~
	 * 	 * 中文解释：
	 * 	 * 如果没有通过setActiveProfiles显式激活其他配置文件，则指定默认情况下将其激活的配置文件集
	 * 	 * ~~~~~~~~~~~~~~
	 * @throws IllegalArgumentException if any profile is null, empty or whitespace-only
	 * @see AbstractEnvironment#DEFAULT_PROFILES_PROPERTY_NAME
	 */
	void setDefaultProfiles(String... profiles);

	/**
	 * Return the {@link PropertySources} for this {@code Environment} in mutable form,
	 * allowing for manipulation of the set of {@link PropertySource} objects that should
	 * be searched when resolving properties against this {@code Environment} object.
	 * The various {@link MutablePropertySources} methods such as
	 *
	 *  * ~~~~~~~~~~~~~~~~~~~
	 * 	 * 以可变形式返回此Environment的PropertySources ，
	 * 	 * 从而允许处理在针对该Environment对象解析属性时应搜索的PropertySource对象集。
	 * 	 * 各种MutablePropertySources方法
	 * 	 * （例如addFirst ， addLast ， addBefore和addAfter允许对属性源顺序进行细粒度控制。
	 * 	 *  例如，这在确保某些用户定义的属性源具有优先于默认属性源
	 * 	 * （例如系统属性集或系统环境变量集）的搜索优先级时很有用
	 * 	 * ~~~~~~~~~~~~~~~~~~~
	 * 	 * 其实我是没有理解这个是什么作用，意思就是说返回一个可变的环境属性，提供各种方法来操作配置属性优先级。
	 * 	 * 这里姑且算是，设置谁先执行，谁先生效
	 * 	 * ~~~~~~~~~~~~~~~~~~~

	 * {@link MutablePropertySources#addFirst addFirst},
	 * {@link MutablePropertySources#addLast addLast},
	 * {@link MutablePropertySources#addBefore addBefore} and
	 * {@link MutablePropertySources#addAfter addAfter} allow for fine-grained control
	 * over property source ordering. This is useful, for example, in ensuring that
	 * certain user-defined property sources have search precedence over default property
	 * sources such as the set of system properties or the set of system environment
	 * variables.
	 * @see AbstractEnvironment#customizePropertySources
	 */
	MutablePropertySources getPropertySources();

	/**
	 * Return the value of {@link System#getProperties()} if allowed by the current
	 * {@link SecurityManager}, otherwise return a map implementation that will attempt
	 * to access individual keys using calls to {@link System#getProperty(String)}.
	 * <p>Note that most {@code Environment} implementations will include this system
	 * properties map as a default {@link PropertySource} to be searched. Therefore, it is
	 * recommended that this method not be used directly unless bypassing other property
	 * sources is expressly intended.
	 *  * ~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 中文解释：
	 * 	 * 如果当前SecurityManager允许，返回System.getProperties()的值，
	 * 	 * 否则返回一个映射实现，该实现将尝试使用对System.getProperty(String)调用来访问各个键
	 * 	 * 请注意，大多数Environment实施都将包含此系统属性映射，作为要搜索的默认PropertySource 。
	 * 	 * 因此，建议不要直接使用此方法，除非明确打算绕过其他属性源。
	 * 	 * 在返回的Map上调用Map.get(Object)永远不会抛出IllegalAccessException ;
	 * 	 * 如果SecurityManager禁止访问属性，则将返回null并发出INFO级别的日志消息，指出该异常
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 通过getSecurityManager()获取SecurityManager来判断是否被允许获取系统属性
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~

	 * <p>Calls to {@link Map#get(Object)} on the Map returned will never throw
	 * {@link IllegalAccessException}; in cases where the SecurityManager forbids access
	 * to a property, {@code null} will be returned and an INFO-level log message will be
	 * issued noting the exception.
	 */
	Map<String, Object> getSystemProperties();

	/**
	 * Return the value of {@link System#getenv()} if allowed by the current
	 * {@link SecurityManager}, otherwise return a map implementation that will attempt
	 * to access individual keys using calls to {@link System#getenv(String)}.
	 * <p>Note that most {@link Environment} implementations will include this system
	 * environment map as a default {@link PropertySource} to be searched. Therefore, it
	 * is recommended that this method not be used directly unless bypassing other
	 * property sources is expressly intended.
	 * * ~~~~~~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 如果当前SecurityManager允许，返回System.getenv()的值，
	 * 	 * 否则返回一个映射实现，该实现将尝试使用对System.getenv(String)调用来访问各个键。
	 * 	 * 请注意，大多数Environment实现都将包含此系统环境映射作为要搜索的默认PropertySource 。
	 * 	 * 因此，建议不要直接使用此方法，除非明确打算绕过其他属性源。
	 * 	 * 在返回的Map上调用Map.get(Object)永远不会抛出IllegalAccessException ;
	 * 	 * 如果SecurityManager禁止访问属性，则将返回null并发出INFO级别的日志消息，指出该异常
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~

	 * <p>Calls to {@link Map#get(Object)} on the Map returned will never throw
	 * {@link IllegalAccessException}; in cases where the SecurityManager forbids access
	 * to a property, {@code null} will be returned and an INFO-level log message will be
	 * issued noting the exception.
	 */
	Map<String, Object> getSystemEnvironment();

	/**
	 * Append the given parent environment's active profiles, default profiles and
	 * property sources to this (child) environment's respective collections of each.
	 * <p>For any identically-named {@code PropertySource} instance existing in both
	 * parent and child, the child instance is to be preserved and the parent instance
	 * discarded. This has the effect of allowing overriding of property sources by the
	 * child as well as avoiding redundant searches through common property source types,
	 * e.g. system environment and system properties.
	 * <p>Active and default profile names are also filtered for duplicates, to avoid
	 * confusion and redundant storage.
	 *
	 *
	 * * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 将给定的父环境的活动配置文件，默认配置文件和属性源追加到此（子）环境各自的集合中。
	 * 	 * 对于父代和子代中都存在的任何名称相同的PropertySource实例，将保留子代实例，并丢弃父代实例。
	 * 	 * 这样的效果是允许子级覆盖属性源，并避免通过常见属性源类型（例如系统环境和系统属性）进行冗余搜索。
	 * 	 * 活动和默认配置文件名称也会被过滤，以防重复，以避免混淆和冗余存储。
	 * 	 * 在任何情况下，父环境都保持不变。
	 * 	 * 请注意，在merge调用之后对父环境所做的任何更改都不会反映在子项中。
	 * 	 * 因此，在调用merge之前，应谨慎配置父​​属性源和配置文件信息
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	 * 	 * 默认情况下父环境的配置文件遗传到子环境配置文件，单是出现两者同名的配置文件，
	 * 	 * 子类还是用自己的配置文件，而不是用父环境的配置文件，就近原则。
	 * 	 * 在调用这个方法merge()之前父配置文件的更改是会同步到子环境中，但是这个方法的地调用，
	 * 	 * 这种特性就消失了，子环境自己玩，不受到付环境的影响。
	 * 	 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	 * <p>The parent environment remains unmodified in any case. Note that any changes to
	 * the parent environment occurring after the call to {@code merge} will not be
	 * reflected in the child. Therefore, care should be taken to configure parent
	 * property sources and profile information prior to calling {@code merge}.
	 * @param parent the environment to merge with
	 * @since 3.1.2
	 * @see org.springframework.context.support.AbstractApplicationContext#setParent
	 */
	void merge(ConfigurableEnvironment parent);

}
