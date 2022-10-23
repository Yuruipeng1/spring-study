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

package org.springframework.beans;

import java.io.Serializable;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * Object to hold information and value for an individual bean property.
 * Using an object here, rather than just storing all properties in
 * a map keyed by property name, allows for more flexibility, and the
 * ability to handle indexed properties etc in an optimized way.
 *
 * <p>Note that the value doesn't need to be the final required type:
 * A {@link BeanWrapper} implementation should handle any necessary conversion,
 * as this object doesn't know anything about the objects it will be applied to.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 13 May 2001
 * @see PropertyValues
 * @see BeanWrapper
 */
@SuppressWarnings("serial")
public class PropertyValue extends BeanMetadataAttributeAccessor implements Serializable {

	//属性名
	private final String name;

	@Nullable
	//原始值
	private final Object value;

	//属性是否是一个Optional容器
	private boolean optional = false;

	//是否被转换
	private boolean converted = false;

	@Nullable
	//转换后的值
	private Object convertedValue;

	/** Package-visible field that indicates whether conversion is necessary. */
	@Nullable
	//是否需要被转换
	volatile Boolean conversionNecessary;

	/** Package-visible field for caching the resolved property path tokens. */
	@Nullable
	transient volatile Object resolvedTokens;


	/**
	 * Create a new PropertyValue instance.
	 * @param name the name of the property (never {@code null})
	 * @param value the value of the property (possibly before type conversion)
	 */
	public PropertyValue(String name, @Nullable Object value) {
		Assert.notNull(name, "Name must not be null");
		this.name = name;
		this.value = value;
	}

	/**
	 * Copy constructor.
	 * @param original the PropertyValue to copy (never {@code null})
	 */
	public PropertyValue(PropertyValue original) {
		Assert.notNull(original, "Original must not be null");
		this.name = original.getName();
		this.value = original.getValue();
		this.optional = original.isOptional();
		this.converted = original.converted;
		this.convertedValue = original.convertedValue;
		this.conversionNecessary = original.conversionNecessary;
		this.resolvedTokens = original.resolvedTokens;
		setSource(original.getSource());
		copyAttributesFrom(original);
	}

	/**
	 * Constructor that exposes a new value for an original value holder.
	 * The original holder will be exposed as source of the new holder.
	 * @param original the PropertyValue to link to (never {@code null})
	 * @param newValue the new value to apply
	 */
	public PropertyValue(PropertyValue original, @Nullable Object newValue) {
		Assert.notNull(original, "Original must not be null");
		this.name = original.getName();
		//新值给了value字段
		this.value = newValue;
		this.optional = original.isOptional();
		this.conversionNecessary = original.conversionNecessary;
		this.resolvedTokens = original.resolvedTokens;
		//BeanMetadataElement定义的方法在这里就用到了，保存原始的PropertyValue
		setSource(original);
		//还需要拷贝AttributeAccessor接口实现的Map集合
		copyAttributesFrom(original);
	}


	/**
	 * Return the name of the property.
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Return the value of the property.
	 * <p>Note that type conversion will <i>not</i> have occurred here.
	 * It is the responsibility of the BeanWrapper implementation to
	 * perform type conversion.
	 */
	@Nullable
	public Object getValue() {
		return this.value;
	}

	/**
	 * Return the original PropertyValue instance for this value holder.
	 * @return the original PropertyValue (either a source of this
	 * value holder or this value holder itself).
	 */
	//获取原始的PropertyValue对象
	public PropertyValue getOriginalPropertyValue() {
		PropertyValue original = this;
		//原始的PropertyValue通过setSource()方法保存进去，现在通过getSource()方法取出
		Object source = getSource();
		//循环，取出最原始的PropertyValue
		while (source instanceof PropertyValue && source != original) {
			original = (PropertyValue) source;
			source = original.getSource();
		}
		return original;
	}

	/**
	 * Set whether this is an optional value, that is, to be ignored
	 * when no corresponding property exists on the target class.
	 * @since 3.0
	 */
	public void setOptional(boolean optional) {
		this.optional = optional;
	}

	/**
	 * Return whether this is an optional value, that is, to be ignored
	 * when no corresponding property exists on the target class.
	 * @since 3.0
	 */
	public boolean isOptional() {
		return this.optional;
	}

	/**
	 * Return whether this holder contains a converted value already ({@code true}),
	 * or whether the value still needs to be converted ({@code false}).
	 */
	public synchronized boolean isConverted() {
		return this.converted;
	}

	/**
	 * Set the converted value of this property value,
	 * after processed type conversion.
	 */
	//保存已经类型转换成功的值到PropertyValue中
	public synchronized void setConvertedValue(@Nullable Object value) {
		//表示已类型转换
		this.converted = true;
		//保存到了convertedValue属性中
		this.convertedValue = value;
	}

	/**
	 * Return the converted value of this property value,
	 * after processed type conversion.
	 */
	@Nullable
	public synchronized Object getConvertedValue() {
		return this.convertedValue;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof PropertyValue)) {
			return false;
		}
		PropertyValue otherPv = (PropertyValue) other;
		return (this.name.equals(otherPv.name) &&
				ObjectUtils.nullSafeEquals(this.value, otherPv.value) &&
				ObjectUtils.nullSafeEquals(getSource(), otherPv.getSource()));
	}

	@Override
	public int hashCode() {
		return this.name.hashCode() * 29 + ObjectUtils.nullSafeHashCode(this.value);
	}

	@Override
	public String toString() {
		return "bean property '" + this.name + "'";
	}

}
