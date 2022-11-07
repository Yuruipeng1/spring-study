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

package org.springframework.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;

/**
 * Utility class for working with Strings that have placeholder values in them.
 * A placeholder takes the form {@code ${name}}. Using {@code PropertyPlaceholderHelper}
 * these placeholders can be substituted for user-supplied values.
 *
 * <p>Values for substitution can be supplied using a {@link Properties} instance or
 * using a {@link PlaceholderResolver}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 3.0
 */
public class PropertyPlaceholderHelper {

	private static final Log logger = LogFactory.getLog(PropertyPlaceholderHelper.class);

	private static final Map<String, String> wellKnownSimplePrefixes = new HashMap<>(4);

	static {
		wellKnownSimplePrefixes.put("}", "{");
		wellKnownSimplePrefixes.put("]", "[");
		wellKnownSimplePrefixes.put(")", "(");
	}


	private final String placeholderPrefix;

	private final String placeholderSuffix;

	private final String simplePrefix;

	@Nullable
	private final String valueSeparator;

	private final boolean ignoreUnresolvablePlaceholders;


	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * Unresolvable placeholders are ignored.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix) {
		this(placeholderPrefix, placeholderSuffix, null, true);
	}

	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 * @param valueSeparator the separating character between the placeholder variable
	 * and the associated default value, if any
	 * @param ignoreUnresolvablePlaceholders indicates whether unresolvable placeholders should
	 * be ignored ({@code true}) or cause an exception ({@code false})
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix,
			@Nullable String valueSeparator, boolean ignoreUnresolvablePlaceholders) {

		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		this.placeholderPrefix = placeholderPrefix;
		this.placeholderSuffix = placeholderSuffix;
		String simplePrefixForSuffix = wellKnownSimplePrefixes.get(this.placeholderSuffix);
		if (simplePrefixForSuffix != null && this.placeholderPrefix.endsWith(simplePrefixForSuffix)) {
			this.simplePrefix = simplePrefixForSuffix;
		}
		else {
			this.simplePrefix = this.placeholderPrefix;
		}
		this.valueSeparator = valueSeparator;
		this.ignoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
	}


	/**
	 * Replaces all placeholders of format {@code ${name}} with the corresponding
	 * property from the supplied {@link Properties}.
	 * @param value the value containing the placeholders to be replaced
	 * @param properties the {@code Properties} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, final Properties properties) {
		Assert.notNull(properties, "'properties' must not be null");
		return replacePlaceholders(value, properties::getProperty);
	}

	/**
	 * Replaces all placeholders of format {@code ${name}} with the value returned
	 * from the supplied {@link PlaceholderResolver}.
	 * @param value the value containing the placeholders to be replaced
	 * @param placeholderResolver the {@code PlaceholderResolver} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 * 替换value中所有${name}格式的字符串，值调用PlaceholderResolver获取，实际上就是调用getPropertyAsRawString获取
	 */
	public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
		Assert.notNull(value, "'value' must not be null");
		//开始解析
		return parseStringValue(value, placeholderResolver, null);
	}

	protected String parseStringValue(
			String value, PlaceholderResolver placeholderResolver, @Nullable Set<String> visitedPlaceholders) {

		//返回第一个${的下标
		int startIndex = value.indexOf(this.placeholderPrefix);
		//下标为-1说明配置文件名称中没有占位符，不用解析，直接返回
		if (startIndex == -1) {
			return value;
		}

		//创建一个线程不安全的可变字符串对象
		StringBuilder result = new StringBuilder(value);
		//一次循环代表解析了一个完整的占位符
		while (startIndex != -1) {
			//获取占位符的后缀的下标
			int endIndex = findPlaceholderEndIndex(result, startIndex);
			//不等于-1 说明格式正确
			if (endIndex != -1) {
				//截取出占位符中的key
				String placeholder = result.substring(startIndex + this.placeholderPrefix.length(), endIndex);
				String originalPlaceholder = placeholder;
				//第一次肯定是空的，调用该方法是参数是null
				if (visitedPlaceholders == null) {
					visitedPlaceholders = new HashSet<>(4);
				}
				//set集合里面的元素不能重复，即占位符的key不能重复
				if (!visitedPlaceholders.add(originalPlaceholder)) {
					throw new IllegalArgumentException(
							"Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
				}
				// Recursive invocation, parsing placeholders contained in the placeholder key.
				//递归调用，解析占位符key中的占位符（占位符中又嵌套占位符
				placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
				// Now obtain the value for the fully resolved key...
				//解析占位符key获取对应的值
				String propVal = placeholderResolver.resolvePlaceholder(placeholder);
				if (propVal == null && this.valueSeparator != null) {
					//从环境中获取值失败
					//占位符key中可能包含分隔符：
					//对分隔符进行处理
					int separatorIndex = placeholder.indexOf(this.valueSeparator);
					if (separatorIndex != -1) {
						String actualPlaceholder = placeholder.substring(0, separatorIndex);
						String defaultValue = placeholder.substring(separatorIndex + this.valueSeparator.length());
						//再次从环境中获解析
						propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
						if (propVal == null) {
							propVal = defaultValue;
						}
					}
				}
				if (propVal != null) {
					// Recursive invocation, parsing placeholders contained in the
					// previously resolved placeholder value.
					//递归调用，再次解析值中的占位符
					propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
					//将可变字符串中的占位符替换为解析好的值
					result.replace(startIndex, endIndex + this.placeholderSuffix.length(), propVal);
					if (logger.isTraceEnabled()) {
						logger.trace("Resolved placeholder '" + placeholder + "'");
					}
					//获取下一个占位符${的下标（${}-${}类型）
					//-1说明没有下一个，结束循环
					startIndex = result.indexOf(this.placeholderPrefix, startIndex + propVal.length());
				}
				else if (this.ignoreUnresolvablePlaceholders) {
					// Proceed with unprocessed value.
					//true忽略无法解析的占位符
					startIndex = result.indexOf(this.placeholderPrefix, endIndex + this.placeholderSuffix.length());
				}
				else {
					//false无法解析就报异常
					throw new IllegalArgumentException("Could not resolve placeholder '" +
							placeholder + "'" + " in value \"" + value + "\"");
				}
				//从集合中删除已解析的占位符key
				visitedPlaceholders.remove(originalPlaceholder);
			}
			else {
				//endIndex等于-1 说明格式错误
				//startIndex设为-1终止循环
				startIndex = -1;
			}
		}
		return result.toString();
	}

	/**
	 * @param buf 这个就是你输入的配置文件的名字
	 * @param startIndex ${在buf中下标
	 */
	private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
		//获取${的下一个字符的下标
		int index = startIndex + this.placeholderPrefix.length();
		int withinNestedPlaceholder = 0;
		while (index < buf.length()) {
			//匹配buf字符串中index下标对应的字符是否为}
			if (StringUtils.substringMatch(buf, index, this.placeholderSuffix)) {
				if (withinNestedPlaceholder > 0) {
					//匹配到了}，计数减一
					withinNestedPlaceholder--;
					index = index + this.placeholderSuffix.length();
				}
				else {
					//匹配到了${的另一半}
					return index;
				}
			}
			//匹配buf字符串中index下标对应的字符是否为{
			//说明在${}中嵌套了{}，
			else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
				//withinNestedPlaceholder计数里面嵌套了一个{}
				withinNestedPlaceholder++;
				index = index + this.simplePrefix.length();
			}
			else {
				index++;
			}
		}
		//返回-1说明占位符格式错误，可能是少了}
		return -1;
	}


	/**
	 * Strategy interface used to resolve replacement values for placeholders contained in Strings.
	 */
	@FunctionalInterface
	public interface PlaceholderResolver {

		/**
		 * Resolve the supplied placeholder name to the replacement value.
		 * @param placeholderName the name of the placeholder to resolve
		 * @return the replacement value, or {@code null} if no replacement is to be made
		 */
		@Nullable
		String resolvePlaceholder(String placeholderName);
	}

}
