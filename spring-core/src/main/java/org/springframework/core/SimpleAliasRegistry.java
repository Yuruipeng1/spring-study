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

package org.springframework.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Simple implementation of the {@link AliasRegistry} interface.
 * <p>Serves as base class for
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * implementations.
 *
 * @author Juergen Hoeller
 * @author Qimiao Chen
 * @since 2.5.2
 */
//实现了AliasRegistry接口，用ConcurrentHashMap存储别名的对应关系，别名作为key，name作为value。
public class SimpleAliasRegistry implements AliasRegistry {

	/** Logger available to subclasses. */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Map from alias to canonical name. */
	private final Map<String, String> aliasMap = new ConcurrentHashMap<>(16);


	@Override
	public void registerAlias(String name, String alias) {
		Assert.hasText(name, "'name' must not be empty");
		Assert.hasText(alias, "'alias' must not be empty");
		synchronized (this.aliasMap) {
			//如果别名和name一样，则移除这个别名。
			if (alias.equals(name)) {
				this.aliasMap.remove(alias);
				if (logger.isDebugEnabled()) {
					logger.debug("Alias definition '" + alias + "' ignored since it points to same name");
				}
			}
			else {
				//获取注册名
				String registeredName = this.aliasMap.get(alias);
				//如果注册过
				if (registeredName != null) {
					//判断是否是当前的注册名，是的话直接返回
					if (registeredName.equals(name)) {
						// An existing alias - no need to re-register
						return;
					}
					//如果不允许覆盖，就抛异常
					if (!allowAliasOverriding()) {
						throw new IllegalStateException("Cannot define alias '" + alias + "' for name '" +
								name + "': It is already registered for name '" + registeredName + "'.");
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Overriding alias '" + alias + "' definition for registered name '" +
								registeredName + "' with new target name '" + name + "'");
					}
				}
				//判断是否循环
				checkForAliasCircle(name, alias);
				this.aliasMap.put(alias, name);
				if (logger.isTraceEnabled()) {
					logger.trace("Alias definition '" + alias + "' registered for name '" + name + "'");
				}
			}
		}
	}

	/**
	 * Determine whether alias overriding is allowed.
	 * <p>Default is {@code true}.
	 */
	//返回是否允许别名覆盖
	protected boolean allowAliasOverriding() {
		//默认覆盖
		return true;
	}

	/**
	 * Determine whether the given name has the given alias registered.
	 * @param name the name to check
	 * @param alias the alias to look for
	 * @since 4.2.1
	 */
	//是否有别名，这里要判断循环依赖
	public boolean hasAlias(String name, String alias) {
		String registeredName = this.aliasMap.get(alias);
		return ObjectUtils.nullSafeEquals(registeredName, name) || (registeredName != null
				&& hasAlias(name, registeredName));
	}

	@Override
	//移除别名
	public void removeAlias(String alias) {
		synchronized (this.aliasMap) {
			String name = this.aliasMap.remove(alias);
			// 没有对应的别名，抛异常
			if (name == null) {
				throw new IllegalStateException("No alias '" + alias + "' registered");
			}
		}
	}

	@Override
	//是否存在别名
	public boolean isAlias(String name) {
		// 直接在aliasMap里判断
		return this.aliasMap.containsKey(name);
	}

	@Override
	//根据bean名称获取别名
	public String[] getAliases(String name) {
		List<String> result = new ArrayList<>();
		synchronized (this.aliasMap) {
			// 主要的方法
			retrieveAliases(name, result);
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Transitively retrieve all aliases for the given name.
	 * @param name the target name to find aliases for
	 * @param result the resulting aliases list
	 */
	//获取别名的实际方法，根据别名递归查找，直至找到所有的别名。
	private void retrieveAliases(String name, List<String> result) {
		this.aliasMap.forEach((alias, registeredName) -> {
			if (registeredName.equals(name)) {
				//如果注册名和当前name一样，用对应的别名，继续查找
				//比如a，b一组，b，c一组，我们想获取a的别名，找到b后，会用b继续查找，找到c，此时，得到的值就是b、c
				result.add(alias);
				SimpleAliasRegistry.this.retrieveAliases(alias, result);
			}
		});
	}

	/**
	 * Resolve all alias target names and aliases registered in this
	 * registry, applying the given {@link StringValueResolver} to them.
	 * <p>The value resolver may for example resolve placeholders
	 * in target bean names and even in alias names.
	 * @param valueResolver the StringValueResolver to apply
	 */
	public void resolveAliases(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		synchronized (this.aliasMap) {
			Map<String, String> aliasCopy = new HashMap<>(this.aliasMap);
			aliasCopy.forEach((alias, registeredName) -> {
				// 获取解析后的别名
				String resolvedAlias = valueResolver.resolveStringValue(alias);
				// 获取解析后的注册名
				String resolvedName = valueResolver.resolveStringValue(registeredName);
				// 为空或者两个相等，移除掉
				if (resolvedAlias == null || resolvedName == null || resolvedAlias.equals(resolvedName)) {
					this.aliasMap.remove(alias);
				}
				else if (!resolvedAlias.equals(alias)) {// 别名和解析后的别名不一样
					// 获取注册名
					String existingName = this.aliasMap.get(resolvedAlias);
					// 如果存在
					if (existingName != null) {
						// 获取到的就是解析后的注册名，就把别名对应的移除。如果不一样，说明解析后的注册名被占用了，就抛异常
						if (existingName.equals(resolvedName)) {
							// Pointing to existing alias - just remove placeholder
							this.aliasMap.remove(alias);
							return;
						}
						throw new IllegalStateException(
								"Cannot register resolved alias '" + resolvedAlias + "' (original: '" + alias +
								"') for name '" + resolvedName + "': It is already registered for name '" +
								registeredName + "'.");
					}
					//判断是否循环
					checkForAliasCircle(resolvedName, resolvedAlias);
					// 移除alias并设置解析后的映射
					this.aliasMap.remove(alias);
					this.aliasMap.put(resolvedAlias, resolvedName);
				}
				else if (!registeredName.equals(resolvedName)) {
					this.aliasMap.put(alias, resolvedName);
				}
			});
		}
	}

	/**
	 * Check whether the given name points back to the given alias as an alias
	 * in the other direction already, catching a circular reference upfront
	 * and throwing a corresponding IllegalStateException.
	 * @param name the candidate name
	 * @param alias the candidate alias
	 * @see #registerAlias
	 * @see #hasAlias
	 */
	protected void checkForAliasCircle(String name, String alias) {
		// 调用hasAlias，循环调用抛异常
		if (hasAlias(alias, name)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Circular reference - '" +
					name + "' is a direct or indirect alias for '" + alias + "' already");
		}
	}

	/**
	 * Determine the raw name, resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed name
	 */
	public String canonicalName(String name) {
		String canonicalName = name;
		// Handle aliasing...
		String resolvedName;
		do {
			resolvedName = this.aliasMap.get(canonicalName);
			if (resolvedName != null) {
				canonicalName = resolvedName;
			}
		}
		while (resolvedName != null);
		return canonicalName;
	}

}
