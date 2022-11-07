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

import java.beans.PropertyEditor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.NumberUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Internal helper class for converting property values to target types.
 *
 * <p>Works on a given {@link PropertyEditorRegistrySupport} instance.
 * Used as a delegate by {@link BeanWrapperImpl} and {@link SimpleTypeConverter}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @since 2.0
 * @see BeanWrapperImpl
 * @see SimpleTypeConverter
 */
class TypeConverterDelegate {

	private static final Log logger = LogFactory.getLog(TypeConverterDelegate.class);

	//PropertyEditor注册中心
	private final PropertyEditorRegistrySupport propertyEditorRegistry;

	//BeanWrapperImpl包装的对象
	@Nullable
	private final Object targetObject;


	/**
	 * Create a new TypeConverterDelegate for the given editor registry.
	 * @param propertyEditorRegistry the editor registry to use
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry) {
		this(propertyEditorRegistry, null);
	}

	/**
	 * Create a new TypeConverterDelegate for the given editor registry and bean instance.
	 * @param propertyEditorRegistry the editor registry to use
	 * @param targetObject the target object to work on (as context that can be passed to editors)
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry, @Nullable Object targetObject) {
		//实际上就是BeanWrapperImpl
		this.propertyEditorRegistry = propertyEditorRegistry;
		//BeanWrapperImpl包装的对象
		this.targetObject = targetObject;
	}


	/**
	 * Convert the value to the required type for the specified property.
	 * @param propertyName name of the property
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
			Object newValue, @Nullable Class<T> requiredType) throws IllegalArgumentException {

		return convertIfNecessary(propertyName, oldValue, newValue, requiredType, TypeDescriptor.valueOf(requiredType));
	}

	/**
	 * Convert the value to the required type (if necessary from a String),
	 * for the specified property.
	 * @param propertyName name of the property
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param typeDescriptor the descriptor for the target property or field
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue, @Nullable Object newValue,
			@Nullable Class<T> requiredType, @Nullable TypeDescriptor typeDescriptor) throws IllegalArgumentException {

		// Custom editor for this type?
		//从用户自定义的PropertyEditor中找到可以将String->requiredType的PropertyEditor
		PropertyEditor editor = this.propertyEditorRegistry.findCustomEditor(requiredType, propertyName);

		ConversionFailedException conversionAttemptEx = null;

		/*****************************使用conversionService转换*****************************/
		// No custom editor but custom ConversionService specified?
		//获取统一转换服务
		ConversionService conversionService = this.propertyEditorRegistry.getConversionService();
		/**
		 * 只有从用户自定义的PropertyEditor找不到合适的
		 * 才会尝试使用ConversionService进行类型转换
		 */
		if (editor == null && conversionService != null && newValue != null && typeDescriptor != null) {
			//得到待转换值的属性描述
			TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
			/**
			 * 判断统一转换服务能否实现sourceTypeDesc->typeDescriptor的转换
			 * sourceTypeDesc：待转换值的类型描述
			 * typeDescriptor：要求类型的类型描述
			 */
			if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
				try {
					return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
				}
				catch (ConversionFailedException ex) {
					// fallback to default conversion logic below
					conversionAttemptEx = ex;
				}
			}
		}

		Object convertedValue = newValue;

		/********************************使用PropertyEditor转换*****************************/
		// Value not of required type?
		/**
		 * 此处两种情况进入类型转换过程
		 * 1.用户自定义了String->requiredType转换的PropertyEditor
		 * 2.requiredType和现有的值的类型不匹配
		 */
		if (editor != null || (requiredType != null && !ClassUtils.isAssignableValue(requiredType, convertedValue))) {
			//String->Collection
			if (typeDescriptor != null && requiredType != null && Collection.class.isAssignableFrom(requiredType) &&
					convertedValue instanceof String) {
				//获取类型的元素类型（array，collection，stream中存的元素类型）
				TypeDescriptor elementTypeDesc = typeDescriptor.getElementTypeDescriptor();
				if (elementTypeDesc != null) {
					Class<?> elementType = elementTypeDesc.getType();
					//元素类型为clazz或枚举
					if (Class.class == elementType || Enum.class.isAssignableFrom(elementType)) {
						//将逗号分隔的字符串转化为字符串数组
						convertedValue = StringUtils.commaDelimitedListToStringArray((String) convertedValue);
					}
				}
			}
			if (editor == null) {
				//从默认的PropertyEditor中找到可以将String->requiredType转换的PropertyEditor
				editor = findDefaultEditor(requiredType);
			}
			//使用PropertyEditor转换String->requiredType
			convertedValue = doConvertValue(oldValue, convertedValue, requiredType, editor);
		}

		boolean standardConversion = false;

		if (requiredType != null) {
			// Try to apply some standard type conversion rules if appropriate.

			/******************************标准类型转换**************************/
			if (convertedValue != null) {
				//属性是Object类型，则无需类型转换
				if (Object.class == requiredType) {
					return (T) convertedValue;
				}
				//属性是数组类型
				else if (requiredType.isArray()) {
					// Array required -> apply appropriate conversion of elements.
					if (convertedValue instanceof String && Enum.class.isAssignableFrom(requiredType.getComponentType())) {
						convertedValue = StringUtils.commaDelimitedListToStringArray((String) convertedValue);
					}
					return (T) convertToTypedArray(convertedValue, propertyName, requiredType.getComponentType());
				}
				//属性是集合类型
				else if (convertedValue instanceof Collection) {
					// Convert elements to target type, if determined.
					convertedValue = convertToTypedCollection(
							(Collection<?>) convertedValue, propertyName, requiredType, typeDescriptor);
					standardConversion = true;
				}
				//属性是Map集合类型，此处只会转化Map集合key和value的类型，并不是将别的集合转为Map
				else if (convertedValue instanceof Map) {
					// Convert keys and values to respective target type, if determined.
					convertedValue = convertToTypedMap(
							(Map<?, ?>) convertedValue, propertyName, requiredType, typeDescriptor);
					standardConversion = true;
				}
				//属性为非数组类型，但填充值为数组类型，那么就取数组的第一个值进行类型转换之后填充
				if (convertedValue.getClass().isArray() && Array.getLength(convertedValue) == 1) {
					convertedValue = Array.get(convertedValue, 0);
					standardConversion = true;
				}
				/**
				 * isPrimitiveOrWrapper()判断这个类是不是一个原始类型
				 * （ boolean, byte, char, short, int, long, float, or double，void）
				 * 或者包装类型
				 * （Boolean, Byte, Character, Short, Integer, Long, Float,Double, or Void）
				 * 这个分支处理原始或包装类型->String类型的转换
				 */
				if (String.class == requiredType && ClassUtils.isPrimitiveOrWrapper(convertedValue.getClass())) {
					// We can stringify any primitive value...
					return (T) convertedValue.toString();
				}
				/**
				 * 通过构造方法进行类型转换
				 * 直接获取转换类型类的单String参数的构造器，传入需要转换的值
				 * 用户在这个构造器中编写转换逻辑，spring通过这个构造器构造转换成功的对象
				 * 这种方式只能实现String->requiredType的转换
				 */
				else if (convertedValue instanceof String && !requiredType.isInstance(convertedValue)) {
					//requiredType不是接口，不是枚举
					if (conversionAttemptEx == null && !requiredType.isInterface() && !requiredType.isEnum()) {
						try {
							//得到单String参数的构造器
							Constructor<T> strCtor = requiredType.getConstructor(String.class);
							/**
							 * 使用这个构造器实例化一个requiredType对象
							 * 如此也实现了String->requiredType的转换
							 */
							return BeanUtils.instantiateClass(strCtor, convertedValue);
						}
						catch (NoSuchMethodException ex) {
							// proceed with field lookup
							if (logger.isTraceEnabled()) {
								logger.trace("No String constructor found on type [" + requiredType.getName() + "]", ex);
							}
						}
						catch (Exception ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Construction via String failed for type [" + requiredType.getName() + "]", ex);
							}
						}
					}
					//requiredType是枚举,String->enum
					String trimmedValue = ((String) convertedValue).trim();
					if (requiredType.isEnum() && trimmedValue.isEmpty()) {
						// It's an empty enum identifier: reset the enum value to null.
						return null;
					}
					convertedValue = attemptToConvertStringToEnum(requiredType, trimmedValue, convertedValue);
					standardConversion = true;
				}
				else if (convertedValue instanceof Number && Number.class.isAssignableFrom(requiredType)) {
					convertedValue = NumberUtils.convertNumberToTargetClass(
							(Number) convertedValue, (Class<Number>) requiredType);
					standardConversion = true;
				}
			}
			//待转换的值为null,此时创建一个空容器代表已转化的值
			else {
				// convertedValue == null
				if (requiredType == Optional.class) {
					convertedValue = Optional.empty();
				}
			}
			/***********************未转换成功才会运行下面代码**************************/
			//convertedValue是不是requiredType类型
			if (!ClassUtils.isAssignableValue(requiredType, convertedValue)) {
				//抛出前面转换过程产生的异常
				if (conversionAttemptEx != null) {
					// Original exception from former ConversionService call above...
					throw conversionAttemptEx;
				}
				//用户自定义的PropertyEditor未转换成功，此时使用conversionService进行转换
				else if (conversionService != null && typeDescriptor != null) {
					// ConversionService not tried before, probably custom editor found
					// but editor couldn't produce the required type...
					//得到待转换值的类型描述
					TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
					/**
					 * 判断统一转换服务能否实现sourceTypeDesc->typeDescriptor的转换
					 * sourceTypeDesc：待转换值的类型描述
					 * typeDescriptor：要求类型的类型描述
					 */
					if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
						//统一转换服务转换类型
						return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
					}
				}

				//后面都是异常相关处理
				// Definitely doesn't match: throw IllegalArgumentException/IllegalStateException
				StringBuilder msg = new StringBuilder();
				msg.append("Cannot convert value of type '").append(ClassUtils.getDescriptiveType(newValue));
				msg.append("' to required type '").append(ClassUtils.getQualifiedName(requiredType)).append("'");
				if (propertyName != null) {
					msg.append(" for property '").append(propertyName).append("'");
				}
				if (editor != null) {
					msg.append(": PropertyEditor [").append(editor.getClass().getName()).append(
							"] returned inappropriate value of type '").append(
							ClassUtils.getDescriptiveType(convertedValue)).append("'");
					throw new IllegalArgumentException(msg.toString());
				}
				else {
					msg.append(": no matching editors or conversion strategy found");
					throw new IllegalStateException(msg.toString());
				}
			}
		}

		//抛出前面转换过程产生的异常
		if (conversionAttemptEx != null) {
			if (editor == null && !standardConversion && requiredType != null && Object.class != requiredType) {
				throw conversionAttemptEx;
			}
			logger.debug("Original ConversionService attempt failed - ignored since " +
					"PropertyEditor based conversion eventually succeeded", conversionAttemptEx);
		}

		//返回转换成功的值
		return (T) convertedValue;
	}

	private Object attemptToConvertStringToEnum(Class<?> requiredType, String trimmedValue, Object currentConvertedValue) {
		Object convertedValue = currentConvertedValue;

		if (Enum.class == requiredType && this.targetObject != null) {
			// target type is declared as raw enum, treat the trimmed value as <enum.fqn>.FIELD_NAME
			int index = trimmedValue.lastIndexOf('.');
			if (index > - 1) {
				String enumType = trimmedValue.substring(0, index);
				String fieldName = trimmedValue.substring(index + 1);
				ClassLoader cl = this.targetObject.getClass().getClassLoader();
				try {
					Class<?> enumValueType = ClassUtils.forName(enumType, cl);
					Field enumField = enumValueType.getField(fieldName);
					convertedValue = enumField.get(null);
				}
				catch (ClassNotFoundException ex) {
					if (logger.isTraceEnabled()) {
						logger.trace("Enum class [" + enumType + "] cannot be loaded", ex);
					}
				}
				catch (Throwable ex) {
					if (logger.isTraceEnabled()) {
						logger.trace("Field [" + fieldName + "] isn't an enum value for type [" + enumType + "]", ex);
					}
				}
			}
		}

		if (convertedValue == currentConvertedValue) {
			// Try field lookup as fallback: for JDK 1.5 enum or custom enum
			// with values defined as static fields. Resulting value still needs
			// to be checked, hence we don't return it right away.
			try {
				Field enumField = requiredType.getField(trimmedValue);
				ReflectionUtils.makeAccessible(enumField);
				convertedValue = enumField.get(null);
			}
			catch (Throwable ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Field [" + convertedValue + "] isn't an enum value", ex);
				}
			}
		}

		return convertedValue;
	}
	/**
	 * Find a default editor for the given type.
	 * @param requiredType the type to find an editor for
	 * @return the corresponding editor, or {@code null} if none
	 */
	@Nullable
	//根据要求类型requiredType获取默认的PropertyEditor
	private PropertyEditor findDefaultEditor(@Nullable Class<?> requiredType) {
		PropertyEditor editor = null;
		if (requiredType != null) {
			// No custom editor -> check BeanWrapperImpl's default editors.
			//根据类型获取BeanWrapperImpl中默认的PropertyEditor
			editor = this.propertyEditorRegistry.getDefaultEditor(requiredType);
			if (editor == null && String.class != requiredType) {
				// No BeanWrapper default editor -> check standard JavaBean editor.
				/**
				 * 获取后缀约定的PropertyEditor
				 * 什么叫后缀约定，举个例子，现在要将String->Role,那么我们可以直接将这个PropertyEditor
				 * 命名为RoleEditor，不需要自己注册到容器中，容器会自动根据约定获取到这个PropertyEditor
				 * 在这个方法里面会自动的将requiredType拼接上Editor，
				 * 然后反射实例化得到这个PropertyEditor，要求它们在同一个包下
				 */
				editor = BeanUtils.findEditorByConvention(requiredType);
			}
		}
		return editor;
	}

	/**
	 * Convert the value to the required type (if necessary from a String),
	 * using the given property editor.
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newValue the proposed new value
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 * @param editor the PropertyEditor to use
	 * @return the new value, possibly the result of type conversion
	 * @throws IllegalArgumentException if type conversion failed
	 */
	@Nullable
	//进行String->requiredType的类型转换
	private Object doConvertValue(@Nullable Object oldValue, @Nullable Object newValue,
			@Nullable Class<?> requiredType, @Nullable PropertyEditor editor) {

		Object convertedValue = newValue;

		/**
		 * 非String类型->requiredType
		 * 调用PropertyEditor的setValue方法和getValue方法
		 * 也就是说我们可以重写这两个方法对这个非String类型干点啥
		 */
		if (editor != null && !(convertedValue instanceof String)) {
			// Not a String -> use PropertyEditor's setValue.
			// With standard PropertyEditors, this will return the very same object;
			// we just want to allow special PropertyEditors to override setValue
			// for type conversion from non-String values to the required type.
			try {
				editor.setValue(convertedValue);
				Object newConvertedValue = editor.getValue();
				if (newConvertedValue != convertedValue) {
					convertedValue = newConvertedValue;
					// Reset PropertyEditor: It already did a proper conversion.
					// Don't use it again for a setAsText call.
					editor = null;
				}
			}
			catch (Exception ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
				}
				// Swallow and proceed.
			}
		}

		Object returnValue = convertedValue;

		/**
		 * String数组类型->requiredType（String类型）
		 * 仅适用于之前未经PropertyEditor转换的数组
		 */
		if (requiredType != null && !requiredType.isArray() && convertedValue instanceof String[]) {
			// Convert String array to a comma-separated String.
			// Only applies if no PropertyEditor converted the String array before.
			// The CSV String will be passed into a PropertyEditor's setAsText method, if any.
			if (logger.isTraceEnabled()) {
				logger.trace("Converting String array to comma-delimited String [" + convertedValue + "]");
			}
			//字符串数组转化为逗号分隔的字符串
			convertedValue = StringUtils.arrayToCommaDelimitedString((String[]) convertedValue);
		}

		/**
		 * String类型->requiredType
		 * 使用PropertyEditor的setAsText方法进行类型转换
		 */
		if (convertedValue instanceof String) {
			if (editor != null) {
				// Use PropertyEditor's setAsText in case of a String value.
				if (logger.isTraceEnabled()) {
					logger.trace("Converting String to [" + requiredType + "] using property editor [" + editor + "]");
				}
				String newTextValue = (String) convertedValue;
				//调用setAsText方法进行类型转换
				return doConvertTextValue(oldValue, newTextValue, editor);
			}
			//要求类型就是String，无需转换，直接使用
			else if (String.class == requiredType) {
				returnValue = convertedValue;
			}
		}

		return returnValue;
	}

	/**
	 * Convert the given text value using the given property editor.
	 * @param oldValue the previous value, if available (may be {@code null})
	 * @param newTextValue the proposed text value
	 * @param editor the PropertyEditor to use
	 * @return the converted value
	 */
	//spring底层使用PropertyEditor的setAsText()方法进行类型转换，然后再调用getValue()方法将转换成功的值取出。
	private Object doConvertTextValue(@Nullable Object oldValue, String newTextValue, PropertyEditor editor) {
		try {
			/**
			 * 先保存旧值，旧值是用属性的getter方法获取的
			 * 相当于属性的默认值
			 * newTextValue则是用户在xml中定义的经过BeanDefinitionValueResolver初步处理的值
			 */
			editor.setValue(oldValue);
		}
		catch (Exception ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
			}
			// Swallow and proceed.
		}
		//最终在此处调用了setAsText()方法进行类型转换
		editor.setAsText(newTextValue);
		//调用getValue()方法将转换成功的值取出来
		return editor.getValue();
	}

	private Object convertToTypedArray(Object input, @Nullable String propertyName, Class<?> componentType) {
		if (input instanceof Collection) {
			// Convert Collection elements to array elements.
			Collection<?> coll = (Collection<?>) input;
			Object result = Array.newInstance(componentType, coll.size());
			int i = 0;
			for (Iterator<?> it = coll.iterator(); it.hasNext(); i++) {
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, it.next(), componentType);
				Array.set(result, i, value);
			}
			return result;
		}
		else if (input.getClass().isArray()) {
			// Convert array elements, if necessary.
			if (componentType.equals(input.getClass().getComponentType()) &&
					!this.propertyEditorRegistry.hasCustomEditorForElement(componentType, propertyName)) {
				return input;
			}
			int arrayLength = Array.getLength(input);
			Object result = Array.newInstance(componentType, arrayLength);
			for (int i = 0; i < arrayLength; i++) {
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, Array.get(input, i), componentType);
				Array.set(result, i, value);
			}
			return result;
		}
		else {
			// A plain value: convert it to an array with a single component.
			Object result = Array.newInstance(componentType, 1);
			Object value = convertIfNecessary(
					buildIndexedPropertyName(propertyName, 0), null, input, componentType);
			Array.set(result, 0, value);
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private Collection<?> convertToTypedCollection(Collection<?> original, @Nullable String propertyName,
			Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {

		if (!Collection.class.isAssignableFrom(requiredType)) {
			return original;
		}

		boolean approximable = CollectionFactory.isApproximableCollectionType(requiredType);
		if (!approximable && !canCreateCopy(requiredType)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Collection type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Collection as-is");
			}
			return original;
		}

		boolean originalAllowed = requiredType.isInstance(original);
		TypeDescriptor elementType = (typeDescriptor != null ? typeDescriptor.getElementTypeDescriptor() : null);
		if (elementType == null && originalAllowed &&
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			return original;
		}

		Iterator<?> it;
		try {
			it = original.iterator();
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Collection of type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			return original;
		}

		Collection<Object> convertedCopy;
		try {
			if (approximable) {
				convertedCopy = CollectionFactory.createApproximateCollection(original, original.size());
			}
			else {
				convertedCopy = (Collection<Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Collection type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			return original;
		}

		for (int i = 0; it.hasNext(); i++) {
			Object element = it.next();
			String indexedPropertyName = buildIndexedPropertyName(propertyName, i);
			Object convertedElement = convertIfNecessary(indexedPropertyName, null, element,
					(elementType != null ? elementType.getType() : null) , elementType);
			try {
				convertedCopy.add(convertedElement);
			}
			catch (Throwable ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Collection type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Collection as-is: " + ex);
				}
				return original;
			}
			originalAllowed = originalAllowed && (element == convertedElement);
		}
		return (originalAllowed ? original : convertedCopy);
	}

	@SuppressWarnings("unchecked")
	//Map集合的类型转换
	private Map<?, ?> convertToTypedMap(Map<?, ?> original, @Nullable String propertyName,
			Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {

		//要转换的类型非Map集合，跳过转换，因为此处只能进行key和value的转换
		if (!Map.class.isAssignableFrom(requiredType)) {
			return original;
		}

		/**
		 * 判断类型requiredType的类型是不是下面7种之一
		 * Map、SortedMap、NavigableMap、HashMap、LinkedHashMap、TreeMap、EnumMap
		 */
		boolean approximable = CollectionFactory.isApproximableMapType(requiredType);
		if (!approximable && !canCreateCopy(requiredType)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Map type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Map as-is");
			}
			return original;
		}

		//判断经过BeanDefinitionValueResolver初步解析之后的值是不是requiredType的实例
		boolean originalAllowed = requiredType.isInstance(original);
		//获取Map集合key的TypeDescriptor，未指定就返回null
		TypeDescriptor keyType = (typeDescriptor != null ? typeDescriptor.getMapKeyTypeDescriptor() : null);
		//获取Map集合value的TypeDescriptor，未指定就返回null
		TypeDescriptor valueType = (typeDescriptor != null ? typeDescriptor.getMapValueTypeDescriptor() : null);
		if (keyType == null && valueType == null && originalAllowed &&
				/**
				 * 判断是否为propertyName指定了PropertyEditor，此时只根据属性名匹配，
				 * 因为该方法第一个参数为null
				 */
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			return original;
		}

		Iterator<?> it;
		try {
			it = original.entrySet().iterator();
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Map of type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			return original;
		}

		//用来存放经过类型转换的键值对的集合
		Map<Object, Object> convertedCopy;
		try {
			if (approximable) {
				/**
				 * 如果original的类型为EnumMap，那么就创建一个空的和original大小相同的EnumMap
				 * 如果original的类型为SortedMap，那么就创建一个空的和original大小相同的SortedMap
				 * 如果original是其他类型的Map，那么就创建一个空的和original大小相同的LinkedHashMap
				 */
				convertedCopy = CollectionFactory.createApproximateMap(original, original.size());
			}
			else {
				//反射创建一个requiredType类型的map集合
				convertedCopy = (Map<Object, Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Map type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			return original;
		}

		/**********************迭代器遍历map，转换每一个key、value*************************/
		while (it.hasNext()) {
			Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
			Object key = entry.getKey();
			Object value = entry.getValue();
			//生成键属性名
			String keyedPropertyName = buildKeyedPropertyName(propertyName, key);
			//递归调用convertIfNecessary()方法转换key和value
			Object convertedKey = convertIfNecessary(keyedPropertyName, null, key,
					(keyType != null ? keyType.getType() : null), keyType);
			Object convertedValue = convertIfNecessary(keyedPropertyName, null, value,
					(valueType!= null ? valueType.getType() : null), valueType);

			try {
				//转换成功的值放入convertedCopy集合中
				convertedCopy.put(convertedKey, convertedValue);
			}
			catch (Throwable ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Map type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Map as-is: " + ex);
				}
				return original;
			}
			//转换前和转换后的值相同为true，表示original可以直接使用，否则使用转换的值
			originalAllowed = originalAllowed && (key == convertedKey) && (value == convertedValue);
		}
		return (originalAllowed ? original : convertedCopy);
	}

	@Nullable
	private String buildIndexedPropertyName(@Nullable String propertyName, int index) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + index + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	@Nullable
	//生成键属性名
	private String buildKeyedPropertyName(@Nullable String propertyName, Object key) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + key + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	private boolean canCreateCopy(Class<?> requiredType) {
		return (!requiredType.isInterface() && !Modifier.isAbstract(requiredType.getModifiers()) &&
				Modifier.isPublic(requiredType.getModifiers()) && ClassUtils.hasConstructor(requiredType));
	}

}
