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

package org.springframework.beans;

import java.beans.PropertyChangeEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * A basic {@link ConfigurablePropertyAccessor} that provides the necessary
 * infrastructure for all typical use cases.
 *
 * <p>This accessor will convert collection and array values to the corresponding
 * target collections or arrays, if necessary. Custom property editors that deal
 * with collections or arrays can either be written via PropertyEditor's
 * {@code setValue}, or against a comma-delimited String via {@code setAsText},
 * as String arrays are converted in such a format if the array itself is not
 * assignable.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 4.2
 * @see #registerCustomEditor
 * @see #setPropertyValues
 * @see #setPropertyValue
 * @see #getPropertyValue
 * @see #getPropertyType
 * @see BeanWrapper
 * @see PropertyEditorRegistrySupport
 */
public abstract class AbstractNestablePropertyAccessor extends AbstractPropertyAccessor {

	/**
	 * We'll create a lot of these objects, so we don't want a new logger every time.
	 */
	private static final Log logger = LogFactory.getLog(AbstractNestablePropertyAccessor.class);

	private int autoGrowCollectionLimit = Integer.MAX_VALUE;

	//被包装的bean对象
	@Nullable
	Object wrappedObject;

	//嵌套路径
	private String nestedPath = "";

	//根对象
	@Nullable
	Object rootObject;

	/** Map with cached nested Accessors: nested path -> Accessor instance.
	 * 缓存的嵌套属性寄存器实例
	 * */
	@Nullable
	private Map<String, AbstractNestablePropertyAccessor> nestedPropertyAccessors;


	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * Registers default editors.
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor() {
		this(true);
	}

	/**
	 * Create a new empty accessor. Wrapped instance needs to be set afterwards.
	 * @param registerDefaultEditors whether to register default editors
	 * (can be suppressed if the accessor won't need any type conversion)
	 * @see #setWrappedInstance
	 */
	protected AbstractNestablePropertyAccessor(boolean registerDefaultEditors) {
		if (registerDefaultEditors) {
			registerDefaultEditors();
		}
		this.typeConverterDelegate = new TypeConverterDelegate(this);
	}

	/**
	 * Create a new accessor for the given object.
	 * @param object the object wrapped by this accessor
	 */
	protected AbstractNestablePropertyAccessor(Object object) {
		//允许向BeanWrapper中注册默认的PropertyEditor
		registerDefaultEditors();
		//保存被包装的实例
		setWrappedInstance(object);
	}

	/**
	 * Create a new accessor, wrapping a new instance of the specified class.
	 * @param clazz class to instantiate and wrap
	 */
	protected AbstractNestablePropertyAccessor(Class<?> clazz) {
		registerDefaultEditors();
		setWrappedInstance(BeanUtils.instantiateClass(clazz));
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, Object rootObject) {
		registerDefaultEditors();
		setWrappedInstance(object, nestedPath, rootObject);
	}

	/**
	 * Create a new accessor for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this accessor
	 * @param nestedPath the nested path of the object
	 * @param parent the containing accessor (must not be {@code null})
	 */
	protected AbstractNestablePropertyAccessor(Object object, String nestedPath, AbstractNestablePropertyAccessor parent) {
		//该方法见3.1，核心是保存父对象parent.getWrappedInstance()
		setWrappedInstance(object, nestedPath, parent.getWrappedInstance());
		//下面都是一些字段值的的拷贝
		setExtractOldValueForEditor(parent.isExtractOldValueForEditor());
		setAutoGrowNestedPaths(parent.isAutoGrowNestedPaths());
		setAutoGrowCollectionLimit(parent.getAutoGrowCollectionLimit());
		setConversionService(parent.getConversionService());
	}


	/**
	 * Specify a limit for array and collection auto-growing.
	 * <p>Default is unlimited on a plain accessor.
	 */
	public void setAutoGrowCollectionLimit(int autoGrowCollectionLimit) {
		this.autoGrowCollectionLimit = autoGrowCollectionLimit;
	}

	/**
	 * Return the limit for array and collection auto-growing.
	 */
	public int getAutoGrowCollectionLimit() {
		return this.autoGrowCollectionLimit;
	}

	/**
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 * @param object the new target object
	 */
	public void setWrappedInstance(Object object) {
		//这个方法被子类BeanWrapperImpl重写了
		setWrappedInstance(object, "", null);
	}

	/**
	 * Switch the target object, replacing the cached introspection results only
	 * if the class of the new object is different to that of the replaced object.
	 * @param object the new target object
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	public void setWrappedInstance(Object object, @Nullable String nestedPath, @Nullable Object rootObject) {
		//保存当前被包装的实例
		this.wrappedObject = ObjectUtils.unwrapOptional(object);
		Assert.notNull(this.wrappedObject, "Target object must not be null");
		this.nestedPath = (nestedPath != null ? nestedPath : "");
		/**
		 * 这个rootObject是什么，已经有了wrappedObject为什么还需要一个rootObject？
		 * 其实这个rootObject主要用来保存嵌套属性的上级实例
		 * 举个例子， 对于这种情况<property name="role.rolename" value="wx"></property>
		 * 我们只获取到user对象的BeanWrapper肯定不行，因为你根本不可能使用反射跨类调用setRolename
		 * 方法来将值设置到user对象中，必须要获得role的BeanWrapper，然后使用反射修改值。
		 * 在这种情况下，wrappedObject是role对象，而rootObject就是user对象了
		 */
		this.rootObject = (!this.nestedPath.isEmpty() ? rootObject : this.wrappedObject);
		this.nestedPropertyAccessors = null;
		//委托模式，将具体的类型转换逻辑委托给了TypeConverterDelegate
		this.typeConverterDelegate = new TypeConverterDelegate(this, this.wrappedObject);
	}

	public final Object getWrappedInstance() {
		Assert.state(this.wrappedObject != null, "No wrapped object");
		return this.wrappedObject;
	}

	public final Class<?> getWrappedClass() {
		return getWrappedInstance().getClass();
	}

	/**
	 * Return the nested path of the object wrapped by this accessor.
	 */
	public final String getNestedPath() {
		return this.nestedPath;
	}

	/**
	 * Return the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Object getRootInstance() {
		Assert.state(this.rootObject != null, "No root object");
		return this.rootObject;
	}

	/**
	 * Return the class of the root object at the top of the path of this accessor.
	 * @see #getNestedPath
	 */
	public final Class<?> getRootClass() {
		return getRootInstance().getClass();
	}

	@Override
	public void setPropertyValue(String propertyName, @Nullable Object value) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa;
		try {
			nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
					"Nested property in path '" + propertyName + "' does not exist", ex);
		}
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		nestedPa.setPropertyValue(tokens, new PropertyValue(propertyName, value));
	}

	@Override
	public void setPropertyValue(PropertyValue pv) throws BeansException {
		/**
		 * 嵌套属性会进入8.1.1，解析得到了PropertyTokenHolder对象，但是它并没有缓存
		 * 从下面的流程我们也能知道，只有非嵌套属性才会缓存PropertyTokenHolder对象
		 * 并且缓存之后它就进行属性填充了
		 * 所以只有非嵌套属性填充过一次之后它的tokens才不为空
		 * 主要为了解决覆盖问题
		 *  <property name="name" value="lx"></property>
		 *  <property name="name" value="wx"></property>
		 * 此种情况就不用每次解析属性名了
		 */
		PropertyTokenHolder tokens = (PropertyTokenHolder) pv.resolvedTokens;
		if (tokens == null) {
			//获取属性名
			String propertyName = pv.getName();
			AbstractNestablePropertyAccessor nestedPa;
			try {
				/**
				 * 获取属性名对应的属性寄存器
				 *
				 * 如果是嵌套属性，在此处就能获取到它外部属性的属性寄存器了
				 * 这个方法里面会调用外部属性的getter方法得到外部属性对象，再封装为BeanWrapperImpl对象
				 * 也就是属性寄存器，这就是为什么要求getRole()方法在role.rolename赋值前必须有值的原因
				 */
				nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			}
			catch (NotReadablePropertyException ex) {
				throw new NotWritablePropertyException(getRootClass(), this.nestedPath + propertyName,
						"Nested property in path '" + propertyName + "' does not exist", ex);
			}
			/**
			 * getFinalPath(nestedPa, propertyName) 获取属性名最后一部分
			 * 比如role.rolename对应的属性名字就是rolename
			 * getPropertyNameTokens()实际上就是载对rolename做进一步解析，里面可能有[]之类的符号
			 * 新旧名字封装形成PropertyTokenHolder
			 */
			tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
			//非嵌套属性缓存属性名称标记
			if (nestedPa == this) {
				/**
				 * 缓存属性名称标记
				 * pv.getOriginalPropertyValue()获取用来原始的PropertyValue对象
				 * 当我们要填充的属性是一个嵌套属性，且第一次判断它不可写的时候，就跳过了类型转换
				 * 重新构建了一个PropertyValue对象，并将经过BeanDefinitionValueResolver解析的
				 * 属性值设为value字段值，然后调用setSource()方法保存保存原始的PropertyValue对象
				 * 如果不存在就返回this，也就是当前的PropertyValue
				 */
				pv.getOriginalPropertyValue().resolvedTokens = tokens;
			}
			//填充属性
			nestedPa.setPropertyValue(tokens, pv);
		}
		//只有非嵌套属性且二次填充时才会进入该分支
		else {
			//填充属性
			setPropertyValue(tokens, pv);
		}
	}

	protected void setPropertyValue(PropertyTokenHolder tokens, PropertyValue pv) throws BeansException {
		//处理有[]的属性
		if (tokens.keys != null) {
			processKeyedProperty(tokens, pv);
		}
		//处理无[]的属性
		else {
			processLocalProperty(tokens, pv);
		}
	}

	@SuppressWarnings("unchecked")
	private void processKeyedProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		Object propValue = getPropertyHoldingValue(tokens);
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		if (ph == null) {
			throw new InvalidPropertyException(
					getRootClass(), this.nestedPath + tokens.actualName, "No property handler found");
		}
		Assert.state(tokens.keys != null, "No token keys");
		String lastKey = tokens.keys[tokens.keys.length - 1];

		if (propValue.getClass().isArray()) {
			Class<?> requiredType = propValue.getClass().getComponentType();
			int arrayIndex = Integer.parseInt(lastKey);
			Object oldValue = null;
			try {
				if (isExtractOldValueForEditor() && arrayIndex < Array.getLength(propValue)) {
					oldValue = Array.get(propValue, arrayIndex);
				}
				Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
						requiredType, ph.nested(tokens.keys.length));
				int length = Array.getLength(propValue);
				if (arrayIndex >= length && arrayIndex < this.autoGrowCollectionLimit) {
					Class<?> componentType = propValue.getClass().getComponentType();
					Object newArray = Array.newInstance(componentType, arrayIndex + 1);
					System.arraycopy(propValue, 0, newArray, 0, length);
					int lastKeyIndex = tokens.canonicalName.lastIndexOf('[');
					String propName = tokens.canonicalName.substring(0, lastKeyIndex);
					setPropertyValue(propName, newArray);
					propValue = getPropertyValue(propName);
				}
				Array.set(propValue, arrayIndex, convertedValue);
			}
			catch (IndexOutOfBoundsException ex) {
				throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Invalid array index in property path '" + tokens.canonicalName + "'", ex);
			}
		}

		else if (propValue instanceof List) {
			Class<?> requiredType = ph.getCollectionType(tokens.keys.length);
			List<Object> list = (List<Object>) propValue;
			int index = Integer.parseInt(lastKey);
			Object oldValue = null;
			if (isExtractOldValueForEditor() && index < list.size()) {
				oldValue = list.get(index);
			}
			Object convertedValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					requiredType, ph.nested(tokens.keys.length));
			int size = list.size();
			if (index >= size && index < this.autoGrowCollectionLimit) {
				for (int i = size; i < index; i++) {
					try {
						list.add(null);
					}
					catch (NullPointerException ex) {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
								"Cannot set element with index " + index + " in List of size " +
								size + ", accessed using property path '" + tokens.canonicalName +
								"': List does not support filling up gaps with null elements");
					}
				}
				list.add(convertedValue);
			}
			else {
				try {
					list.set(index, convertedValue);
				}
				catch (IndexOutOfBoundsException ex) {
					throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
							"Invalid list index in property path '" + tokens.canonicalName + "'", ex);
				}
			}
		}

		else if (propValue instanceof Map) {
			Class<?> mapKeyType = ph.getMapKeyType(tokens.keys.length);
			Class<?> mapValueType = ph.getMapValueType(tokens.keys.length);
			Map<Object, Object> map = (Map<Object, Object>) propValue;
			// IMPORTANT: Do not pass full property name in here - property editors
			// must not kick in for map keys but rather only for map values.
			TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
			Object convertedMapKey = convertIfNecessary(null, null, lastKey, mapKeyType, typeDescriptor);
			Object oldValue = null;
			if (isExtractOldValueForEditor()) {
				oldValue = map.get(convertedMapKey);
			}
			// Pass full property name and old value in here, since we want full
			// conversion ability for map values.
			Object convertedMapValue = convertIfNecessary(tokens.canonicalName, oldValue, pv.getValue(),
					mapValueType, ph.nested(tokens.keys.length));
			map.put(convertedMapKey, convertedMapValue);
		}

		else {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Property referenced in indexed property path '" + tokens.canonicalName +
					"' is neither an array nor a List nor a Map; returned value was [" + propValue + "]");
		}
	}

	private Object getPropertyHoldingValue(PropertyTokenHolder tokens) {
		// Apply indexes and map keys: fetch value for all keys but the last one.
		Assert.state(tokens.keys != null, "No token keys");
		PropertyTokenHolder getterTokens = new PropertyTokenHolder(tokens.actualName);
		getterTokens.canonicalName = tokens.canonicalName;
		getterTokens.keys = new String[tokens.keys.length - 1];
		System.arraycopy(tokens.keys, 0, getterTokens.keys, 0, tokens.keys.length - 1);

		Object propValue;
		try {
			propValue = getPropertyValue(getterTokens);
		}
		catch (NotReadablePropertyException ex) {
			throw new NotWritablePropertyException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Cannot access indexed value in property referenced " +
					"in indexed property path '" + tokens.canonicalName + "'", ex);
		}

		if (propValue == null) {
			// null map value case
			if (isAutoGrowNestedPaths()) {
				int lastKeyIndex = tokens.canonicalName.lastIndexOf('[');
				getterTokens.canonicalName = tokens.canonicalName.substring(0, lastKeyIndex);
				propValue = setDefaultValue(getterTokens);
			}
			else {
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
						"Cannot access indexed value in property referenced " +
						"in indexed property path '" + tokens.canonicalName + "': returned null");
			}
		}
		return propValue;
	}

	/**
	 * 遍历MutablePropertyValues，按顺序填充每一个PropertyValue属性
	 * 解析填充属性的属性名，得到PropertyTokenHolder属性名称标记（里面有真实属性名和规范属性名）
	 * 调用getLocalPropertyHandler(tokens.actualName)方法得到属性的属性处理器PropertyHandler
	 * 验证属性是否包含setter方法，没有就抛异常
	 * 判断PropertyValue属性是否有必要进行类型转换，没有必要就直接通过PropertyHandler属性处理器ph.setValue(valueToApply)方法完成属性赋值，否则就进入6
	 * 判断PropertyValue属性是否已经经过类型转换，未经过类型转换就先类型转换，然后填充属性值，否则直接填充属性值
	 * @param tokens
	 * @param pv
	 */
	private void processLocalProperty(PropertyTokenHolder tokens, PropertyValue pv) {
		//获取属性名对应的PropertyHandler
		PropertyHandler ph = getLocalPropertyHandler(tokens.actualName);
		//当前属性没有setter方法，那肯定不能进行属性填充了，抛异常
		if (ph == null || !ph.isWritable()) {
			if (pv.isOptional()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Ignoring optional value for property '" + tokens.actualName +
							"' - property not found on bean class [" + getRootClass().getName() + "]");
				}
				return;
			}
			if (this.suppressNotWritablePropertyException) {
				// Optimization for common ignoreUnknown=true scenario since the
				// exception would be caught and swallowed higher up anyway...
				return;
			}
			throw createNotWritablePropertyException(tokens.canonicalName);
		}

		Object oldValue = null;
		try {
			//获取原始值
			Object originalValue = pv.getValue();
			Object valueToApply = originalValue;
			//需要进行类型转换，pv.conversionNecessary默认为null
			if (!Boolean.FALSE.equals(pv.conversionNecessary)) {
				//已经类型转换了
				if (pv.isConverted()) {
					//直接获取类型转换后的值
					valueToApply = pv.getConvertedValue();
				}
				/**
				 * 没有类型转换
				 * 走到这一步一般是嵌套属性(role.rolename)，在嵌套属性的上级对象(role)没有赋值之前
				 * ，你获取不到role对象的BeanWrapperImpl对象，也就不能进行类型转换了。
				 */
				else {
					/**
					 * isExtractOldValueForEditor()判断是否需要提取旧值进行类型转换
					 * 就是判断extractOldValueForEditor字段值，默认为false
					 * ph.isReadable()判断属性有没有getter方法
					 */
					if (isExtractOldValueForEditor() && ph.isReadable()) {
						try {
							//调用属性的getter方法获取属性默认值
							oldValue = ph.getValue();
						}
						catch (Exception ex) {
							if (ex instanceof PrivilegedActionException) {
								ex = ((PrivilegedActionException) ex).getException();
							}
							if (logger.isDebugEnabled()) {
								logger.debug("Could not read previous value of property '" +
										this.nestedPath + tokens.canonicalName + "'", ex);
							}
						}
					}
					/**
					 *
					 * ph.toTypeDescriptor() 得到当前属性的类型描述
					 * convertForProperty()方法，类型转换的核心方法
					 */
					valueToApply = convertForProperty(
							tokens.canonicalName, oldValue, originalValue, ph.toTypeDescriptor());
				}
				//设置是否需要进行需要类型转换的标记，方便下次快速创建对象
				pv.getOriginalPropertyValue().conversionNecessary = (valueToApply != originalValue);
			}
			//反射调用setter方法填充属性
			ph.setValue(valueToApply);
		}
		catch (TypeMismatchException ex) {
			throw ex;
		}
		catch (InvocationTargetException ex) {
			PropertyChangeEvent propertyChangeEvent = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			if (ex.getTargetException() instanceof ClassCastException) {
				throw new TypeMismatchException(propertyChangeEvent, ph.getPropertyType(), ex.getTargetException());
			}
			else {
				Throwable cause = ex.getTargetException();
				if (cause instanceof UndeclaredThrowableException) {
					// May happen e.g. with Groovy-generated methods
					cause = cause.getCause();
				}
				throw new MethodInvocationException(propertyChangeEvent, cause);
			}
		}
		catch (Exception ex) {
			PropertyChangeEvent pce = new PropertyChangeEvent(
					getRootInstance(), this.nestedPath + tokens.canonicalName, oldValue, pv.getValue());
			throw new MethodInvocationException(pce, ex);
		}
	}

	@Override
	@Nullable
	public Class<?> getPropertyType(String propertyName) throws BeansException {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.getPropertyType();
			}
			else {
				// Maybe an indexed/mapped property...
				Object value = getPropertyValue(propertyName);
				if (value != null) {
					return value.getClass();
				}
				// Check to see if there is a custom editor,
				// which might give an indication on the desired target type.
				Class<?> editorType = guessPropertyTypeFromEditors(propertyName);
				if (editorType != null) {
					return editorType;
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		return null;
	}

	@Override
	@Nullable
	public TypeDescriptor getPropertyTypeDescriptor(String propertyName) throws BeansException {
		try {
			AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
			String finalPath = getFinalPath(nestedPa, propertyName);
			PropertyTokenHolder tokens = getPropertyNameTokens(finalPath);
			PropertyHandler ph = nestedPa.getLocalPropertyHandler(tokens.actualName);
			if (ph != null) {
				if (tokens.keys != null) {
					if (ph.isReadable() || ph.isWritable()) {
						return ph.nested(tokens.keys.length);
					}
				}
				else {
					if (ph.isReadable() || ph.isWritable()) {
						return ph.toTypeDescriptor();
					}
				}
			}
		}
		catch (InvalidPropertyException ex) {
			// Consider as not determinable.
		}
		return null;
	}

	@Override
	public boolean isReadableProperty(String propertyName) {
		try {
			PropertyHandler ph = getPropertyHandler(propertyName);
			if (ph != null) {
				return ph.isReadable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be readable.
		}
		return false;
	}

	/**
	 * 判断属性是否可写
	 * @param propertyName the property to check
	 * (may be a nested path and/or an indexed/mapped property)
	 * @return
	 */
	@Override
	public boolean isWritableProperty(String propertyName) {
		try {
			//获取指定属性名的PropertyHandler属性处理器
			PropertyHandler ph = getPropertyHandler(propertyName);
			//判断该属性是否可写（setter方法）
			if (ph != null) {
				return ph.isWritable();
			}
			else {
				// Maybe an indexed/mapped property...
				getPropertyValue(propertyName);
				return true;
			}
		}
		/**
		 * 此处的异常捕获很重要，当我们propertyName是嵌套属性名，且外层属性没有初始值的时候
		 * 获取propertyName对应的PropertyHandler属性处理器就会抛异常,由此处捕获，返回false
		 * 表明该嵌套属性不可写，然后该属性就不会现在进行类型转换，而是等到属性填充的时候（也就是第9章节
		 * 的方法）再进行类型转换
		 */
		catch (InvalidPropertyException ex) {
			// Cannot be evaluated, so can't be writable.
		}
		return false;
	}

	/**
	 * 使用委托模式将内部的复杂的类型转换逻辑交给了TypeConverterDelegate
	 */
	@Nullable
	private Object convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
			@Nullable Object newValue, @Nullable Class<?> requiredType, @Nullable TypeDescriptor td)
			throws TypeMismatchException {

		Assert.state(this.typeConverterDelegate != null, "No TypeConverterDelegate");
		try {
			/**
			 * 具体的类型转换逻辑委派给了TypeConverterDelegate，由这个类的convertIfNecessary方法实现
			 * 该字段初始化是在BeanWrapperImpl初始化的时候，详情见下篇文章
			 */
			return this.typeConverterDelegate.convertIfNecessary(propertyName, oldValue, newValue, requiredType, td);
		}
		catch (ConverterNotFoundException | IllegalStateException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new ConversionNotSupportedException(pce, requiredType, ex);
		}
		catch (ConversionException | IllegalArgumentException ex) {
			PropertyChangeEvent pce =
					new PropertyChangeEvent(getRootInstance(), this.nestedPath + propertyName, oldValue, newValue);
			throw new TypeMismatchException(pce, requiredType, ex);
		}
	}

	@Nullable
	protected Object convertForProperty(
			String propertyName, @Nullable Object oldValue, @Nullable Object newValue, TypeDescriptor td)
			throws TypeMismatchException {

		//调用了内部私有方法实现类型转换
		return convertIfNecessary(propertyName, oldValue, newValue, td.getType(), td);
	}

	@Override
	@Nullable
	public Object getPropertyValue(String propertyName) throws BeansException {
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		PropertyTokenHolder tokens = getPropertyNameTokens(getFinalPath(nestedPa, propertyName));
		return nestedPa.getPropertyValue(tokens);
	}

	/**
	 * 获取bean对象中指定属性对应的值
	 * @param tokens
	 * @return
	 * @throws BeansException
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	protected Object getPropertyValue(PropertyTokenHolder tokens) throws BeansException {
		//规范名
		String propertyName = tokens.canonicalName;
		//真实名
		String actualName = tokens.actualName;
		/**
		 * 获取指定属性名的PropertyHandler属性处理器
		 * 就是通过内省访问User，然后获取role属性的PropertyHandler
		 */
		PropertyHandler ph = getLocalPropertyHandler(actualName);
		//isReadable()判断属性有没有对应的getter方法
		if (ph == null || !ph.isReadable()) {
			throw new NotReadablePropertyException(getRootClass(), this.nestedPath + propertyName);
		}
		try {
			/**
			 * 反射调用属性的getter方法得到属性值
			 * 实际上就是调用role属性的getter方法，获取role对象
			 * 获取到这个role对象就可以修改它的属性值了
			 */
			Object value = ph.getValue();
			/*******************************处理keys********************************/
			if (tokens.keys != null) {
				if (value == null) {
					if (isAutoGrowNestedPaths()) {
						value = setDefaultValue(new PropertyTokenHolder(tokens.actualName));
					}
					else {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
				}
				StringBuilder indexedPropertyName = new StringBuilder(tokens.actualName);
				// apply indexes and map keys
				for (int i = 0; i < tokens.keys.length; i++) {
					String key = tokens.keys[i];
					if (value == null) {
						throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + propertyName,
								"Cannot access indexed value of property referenced in indexed " +
										"property path '" + propertyName + "': returned null");
					}
					else if (value.getClass().isArray()) {
						int index = Integer.parseInt(key);
						value = growArrayIfNecessary(value, index, indexedPropertyName.toString());
						value = Array.get(value, index);
					}
					else if (value instanceof List) {
						int index = Integer.parseInt(key);
						List<Object> list = (List<Object>) value;
						growCollectionIfNecessary(list, index, indexedPropertyName.toString(), ph, i + 1);
						value = list.get(index);
					}
					else if (value instanceof Set) {
						// Apply index to Iterator in case of a Set.
						Set<Object> set = (Set<Object>) value;
						int index = Integer.parseInt(key);
						if (index < 0 || index >= set.size()) {
							throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
									"Cannot get element with index " + index + " from Set of size " +
											set.size() + ", accessed using property path '" + propertyName + "'");
						}
						Iterator<Object> it = set.iterator();
						for (int j = 0; it.hasNext(); j++) {
							Object elem = it.next();
							if (j == index) {
								value = elem;
								break;
							}
						}
					}
					else if (value instanceof Map) {
						Map<Object, Object> map = (Map<Object, Object>) value;
						Class<?> mapKeyType = ph.getResolvableType().getNested(i + 1).asMap().resolveGeneric(0);
						// IMPORTANT: Do not pass full property name in here - property editors
						// must not kick in for map keys but rather only for map values.
						TypeDescriptor typeDescriptor = TypeDescriptor.valueOf(mapKeyType);
						Object convertedMapKey = convertIfNecessary(null, null, key, mapKeyType, typeDescriptor);
						value = map.get(convertedMapKey);
					}
					else {
						throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
								"Property referenced in indexed property path '" + propertyName +
										"' is neither an array nor a List nor a Set nor a Map; returned value was [" + value + "]");
					}
					indexedPropertyName.append(PROPERTY_KEY_PREFIX).append(key).append(PROPERTY_KEY_SUFFIX);
				}
			}
			return value;
		}
		catch (IndexOutOfBoundsException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Index of out of bounds in property path '" + propertyName + "'", ex);
		}
		catch (NumberFormatException | TypeMismatchException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Invalid index in property path '" + propertyName + "'", ex);
		}
		catch (InvocationTargetException ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Getter for property '" + actualName + "' threw exception", ex);
		}
		catch (Exception ex) {
			throw new InvalidPropertyException(getRootClass(), this.nestedPath + propertyName,
					"Illegal attempt to get property '" + actualName + "' threw exception", ex);
		}
	}


	/**
	 * Return the {@link PropertyHandler} for the specified {@code propertyName}, navigating
	 * if necessary. Return {@code null} if not found rather than throwing an exception.
	 * @param propertyName the property to obtain the descriptor for
	 * @return the property descriptor for the specified property,
	 * or {@code null} if not found
	 * @throws BeansException in case of introspection failure
	 */
	@Nullable
	//获取指定属性名的PropertyHandler
	protected PropertyHandler getPropertyHandler(String propertyName) throws BeansException {
		Assert.notNull(propertyName, "Property name must not be null");
		//递归检查并获取属性的PropertyAccessor对象
		AbstractNestablePropertyAccessor nestedPa = getPropertyAccessorForPropertyPath(propertyName);
		/**
		 * getFinalPath()方法，获取最后一个属性名,例如role.rolename,那么调用该方法后就是rolename,见8.2
		 * getLocalPropertyHandler()方法，获取属性处理器
		 */
		return nestedPa.getLocalPropertyHandler(getFinalPath(nestedPa, propertyName));
	}

	/**
	 * Return a {@link PropertyHandler} for the specified local {@code propertyName}.
	 * Only used to reach a property available in the current context.
	 * @param propertyName the name of a local property
	 * @return the handler for that property, or {@code null} if it has not been found
	 */
	@Nullable
	protected abstract PropertyHandler getLocalPropertyHandler(String propertyName);

	/**
	 * Create a new nested property accessor instance.
	 * Can be overridden in subclasses to create a PropertyAccessor subclass.
	 * @param object the object wrapped by this PropertyAccessor
	 * @param nestedPath the nested path of the object
	 * @return the nested PropertyAccessor instance
	 */
	protected abstract AbstractNestablePropertyAccessor newNestedPropertyAccessor(Object object, String nestedPath);

	/**
	 * Create a {@link NotWritablePropertyException} for the specified property.
	 */
	protected abstract NotWritablePropertyException createNotWritablePropertyException(String propertyName);


	private Object growArrayIfNecessary(Object array, int index, String name) {
		if (!isAutoGrowNestedPaths()) {
			return array;
		}
		int length = Array.getLength(array);
		if (index >= length && index < this.autoGrowCollectionLimit) {
			Class<?> componentType = array.getClass().getComponentType();
			Object newArray = Array.newInstance(componentType, index + 1);
			System.arraycopy(array, 0, newArray, 0, length);
			for (int i = length; i < Array.getLength(newArray); i++) {
				Array.set(newArray, i, newValue(componentType, null, name));
			}
			setPropertyValue(name, newArray);
			Object defaultValue = getPropertyValue(name);
			Assert.state(defaultValue != null, "Default value must not be null");
			return defaultValue;
		}
		else {
			return array;
		}
	}

	private void growCollectionIfNecessary(Collection<Object> collection, int index, String name,
			PropertyHandler ph, int nestingLevel) {

		if (!isAutoGrowNestedPaths()) {
			return;
		}
		int size = collection.size();
		if (index >= size && index < this.autoGrowCollectionLimit) {
			Class<?> elementType = ph.getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
			if (elementType != null) {
				for (int i = collection.size(); i < index + 1; i++) {
					collection.add(newValue(elementType, null, name));
				}
			}
		}
	}

	/**
	 * Get the last component of the path. Also works if not nested.
	 * @param pa property accessor to work on
	 * @param nestedPath property path we know is nested
	 * @return last component of the path (the property on the target bean)
	 */
	// 获取属性中最后一部分（针对嵌套属性）
	protected String getFinalPath(AbstractNestablePropertyAccessor pa, String nestedPath) {
		/**
		 * 属性寄存器是当前的BeanWrapperImpl对象
		 * 说明当前属性不是嵌套属性
		 * 那就直接使用当前属性路径
		 */
		if (pa == this) {
			return nestedPath;
		}
		//否则截取属性，得到属性最后一部分
		return nestedPath.substring(PropertyAccessorUtils.getLastNestedPropertySeparatorIndex(nestedPath) + 1);
	}

	/**
	 * Recursively navigate to return a property accessor for the nested property path.
	 * @param propertyPath property path, which may be nested
	 * @return a property accessor for the target bean
	 */
	protected AbstractNestablePropertyAccessor getPropertyAccessorForPropertyPath(String propertyPath) {
		//获取第一个属性分隔符的索引（分隔符为.）
		int pos = PropertyAccessorUtils.getFirstNestedPropertySeparatorIndex(propertyPath);
		// Handle nested properties recursively.
		//嵌套属性,进行递归
		if (pos > -1) {
			//外层的属性role
			String nestedProperty = propertyPath.substring(0, pos);
			//内层的rolename
			String nestedPath = propertyPath.substring(pos + 1);
			//获取role的属性寄存器
			AbstractNestablePropertyAccessor nestedPa = getNestedPropertyAccessor(nestedProperty);
			//递归，判断rolename中是否还有嵌套，获取到最终属性的PropertyAccessor
			return nestedPa.getPropertyAccessorForPropertyPath(nestedPath);
		}
		//非嵌套属性，直接使用当前bean属性寄存器，即当前BeanWrapperImpl对象
		else {
			return this;
		}
	}

	/**
	 * Retrieve a Property accessor for the given nested property.
	 * Create a new one if not found in the cache.
	 * <p>Note: Caching nested PropertyAccessors is necessary now,
	 * to keep registered custom editors for nested properties.
	 * @param nestedProperty property to create the PropertyAccessor for
	 * @return the PropertyAccessor instance, either cached or newly created
	 */
	private AbstractNestablePropertyAccessor getNestedPropertyAccessor(String nestedProperty) {
		//缓存的嵌套属性寄存器
		if (this.nestedPropertyAccessors == null) {
			this.nestedPropertyAccessors = new HashMap<>();
		}
		// Get value of bean property.
		/**
		 * 这里解析属性名，获取规范名，
		 * 还是role，然后新旧名字封装成PropertyTokenHolder
		 */
		PropertyTokenHolder tokens = getPropertyNameTokens(nestedProperty);
		//规范名
		String canonicalName = tokens.canonicalName;
		/**
		 * 获取bean对象中指定属性对应的值
		 * 获取role属性对象，通过getter方法得到
		 * 初次访问value肯定是null，因为我们并没有给role属性设置值
		 */
		Object value = getPropertyValue(tokens);
		/**
		 * role属性为null，但是你却想给role内部的rolename赋值，那肯定抛异常
		 */
		if (value == null || (value instanceof Optional && !((Optional<?>) value).isPresent())) {
			if (isAutoGrowNestedPaths()) {
				value = setDefaultValue(tokens);
			}
			else {
				throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + canonicalName);
			}
		}

		// Lookup cached sub-PropertyAccessor, create new one if not found.
		/**
		 * 到了这一步就说明嵌套属性的外部属性有值，即对于role.rolename来说，role属性有值
		 * 值从哪儿来的，有两种情况
		 * 1.private Role role=new Role(),role有默认值（Field）
		 * 2.调用getRole()方法的返回不为null（Property）
		 * 3.role属性已经进行属性填充了，现在填充role.rolename了，所以此时能够获取到role对象（对应9
		 * 章节中调用该方法）
		 *
		 * 8章节对应的方法会在两个地方法被调用，第一个是7中的isWritableProperty()方法中调用一次，
		 * 此时若是嵌套属性，通过getter方法访问外部属性得到null，那么此时嵌套属性就不可写，不会马上进行
		 * 类型转换，而是等到9章节方法调用时再尝试一次
		 */
		//再次从缓存中获取一次嵌套属性的属性寄存器
		AbstractNestablePropertyAccessor nestedPa = this.nestedPropertyAccessors.get(canonicalName);
		if (nestedPa == null || nestedPa.getWrappedInstance() != ObjectUtils.unwrapOptional(value)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Creating new nested " + getClass().getSimpleName() + " for property '" + canonicalName + "'");
			}
			//将外部属性对象包装为BeanWrapperImpl对象
			nestedPa = newNestedPropertyAccessor(value, this.nestedPath + canonicalName + NESTED_PROPERTY_SEPARATOR);
			// Inherit all type-specific PropertyEditors.
			//拷贝PropertyEditor到刚创建的BeanWrapperImpl对象中
			copyDefaultEditorsTo(nestedPa);
			copyCustomEditorsTo(nestedPa, canonicalName);
			//缓存嵌套属性的PropertyAccessor
			this.nestedPropertyAccessors.put(canonicalName, nestedPa);
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Using cached nested property accessor for property '" + canonicalName + "'");
			}
		}
		return nestedPa;
	}

	private Object setDefaultValue(PropertyTokenHolder tokens) {
		PropertyValue pv = createDefaultPropertyValue(tokens);
		setPropertyValue(tokens, pv);
		Object defaultValue = getPropertyValue(tokens);
		Assert.state(defaultValue != null, "Default value must not be null");
		return defaultValue;
	}

	private PropertyValue createDefaultPropertyValue(PropertyTokenHolder tokens) {
		TypeDescriptor desc = getPropertyTypeDescriptor(tokens.canonicalName);
		if (desc == null) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + tokens.canonicalName,
					"Could not determine property type for auto-growing a default value");
		}
		Object defaultValue = newValue(desc.getType(), desc, tokens.canonicalName);
		return new PropertyValue(tokens.canonicalName, defaultValue);
	}

	private Object newValue(Class<?> type, @Nullable TypeDescriptor desc, String name) {
		try {
			if (type.isArray()) {
				Class<?> componentType = type.getComponentType();
				// TODO - only handles 2-dimensional arrays
				if (componentType.isArray()) {
					Object array = Array.newInstance(componentType, 1);
					Array.set(array, 0, Array.newInstance(componentType.getComponentType(), 0));
					return array;
				}
				else {
					return Array.newInstance(componentType, 0);
				}
			}
			else if (Collection.class.isAssignableFrom(type)) {
				TypeDescriptor elementDesc = (desc != null ? desc.getElementTypeDescriptor() : null);
				return CollectionFactory.createCollection(type, (elementDesc != null ? elementDesc.getType() : null), 16);
			}
			else if (Map.class.isAssignableFrom(type)) {
				TypeDescriptor keyDesc = (desc != null ? desc.getMapKeyTypeDescriptor() : null);
				return CollectionFactory.createMap(type, (keyDesc != null ? keyDesc.getType() : null), 16);
			}
			else {
				Constructor<?> ctor = type.getDeclaredConstructor();
				if (Modifier.isPrivate(ctor.getModifiers())) {
					throw new IllegalAccessException("Auto-growing not allowed with private constructor: " + ctor);
				}
				return BeanUtils.instantiateClass(ctor);
			}
		}
		catch (Throwable ex) {
			throw new NullValueInNestedPathException(getRootClass(), this.nestedPath + name,
					"Could not instantiate property type [" + type.getName() + "] to auto-grow nested property path", ex);
		}
	}

	/**
	 * Parse the given property name into the corresponding property name tokens.
	 * @param propertyName the property name to parse
	 * @return representation of the parsed property tokens
	 */
	private PropertyTokenHolder getPropertyNameTokens(String propertyName) {
		String actualName = null;
		List<String> keys = new ArrayList<>(2);
		int searchIndex = 0;
		while (searchIndex != -1) {
			/**
			 * PROPERTY_KEY_PREFIX=[
			 * 从索引位置searchIndex开始获得propertyName中[的索引
			 */
			int keyStart = propertyName.indexOf(PROPERTY_KEY_PREFIX, searchIndex);
			searchIndex = -1;
			//属性名中有[
			if (keyStart != -1) {
				//找到与这个[对应的]索引位置
				int keyEnd = getPropertyNameKeyEnd(propertyName, keyStart + PROPERTY_KEY_PREFIX.length());
				//找到了
				if (keyEnd != -1) {
					/**
					 * 只有第一次才会给actualName赋值
					 * 也就是说，propertyName中第一个[符号左边的字符串就是该属性的真实名
					 */
					if (actualName == null) {
						/**
						 * 真实的属性名
						 * 这里截取的是[之前的部分
						 */
						actualName = propertyName.substring(0, keyStart);
					}
					//得到[]符号中的内容
					String key = propertyName.substring(keyStart + PROPERTY_KEY_PREFIX.length(), keyEnd);
					/**
					 * []中的内容有3种写法
					 * 1.什么都不加
					 * 2.'内容'
					 * 3.\内容
					 */
					if (key.length() > 1 && (key.startsWith("'") && key.endsWith("'")) ||
							(key.startsWith("\"") && key.endsWith("\""))) {
						key = key.substring(1, key.length() - 1);
					}
					//保存到keys集合中
					keys.add(key);
					/**
					 * PROPERTY_KEY_SUFFIX=]
					 * 接着去解析下一个[]符号内容
					 * 主要是为了解决wx[xx].de[xx]
					 */
					searchIndex = keyEnd + PROPERTY_KEY_SUFFIX.length();
				}
			}
		}
		/**
		 * 使用真实属性名创建一个属性名称标记对象，见4.2
		 */
		PropertyTokenHolder tokens = new PropertyTokenHolder(actualName != null ? actualName : propertyName);
		//属性名中有[]
		if (!keys.isEmpty()) {
			/**
			 * 属性名中有[]的属性的规范名称
			 * collectionToDelimitedString()方法，将keys集合转[]符号分割的字符串
			 */
			tokens.canonicalName += PROPERTY_KEY_PREFIX +
					StringUtils.collectionToDelimitedString(keys, PROPERTY_KEY_SUFFIX + PROPERTY_KEY_PREFIX) +
					PROPERTY_KEY_SUFFIX;
			//集合转数组，并保存到规范名称标记对象中
			tokens.keys = StringUtils.toStringArray(keys);
		}
		return tokens;
	}

	/**
	 * 找到与[匹配的]
	 * 可能存在[df[dg]]这种情况
	 */
	private int getPropertyNameKeyEnd(String propertyName, int startIndex) {
		int unclosedPrefixes = 0;
		int length = propertyName.length();
		for (int i = startIndex; i < length; i++) {
			switch (propertyName.charAt(i)) {
				case PropertyAccessor.PROPERTY_KEY_PREFIX_CHAR:
					// The property name contains opening prefix(es)...
					unclosedPrefixes++;
					break;
				case PropertyAccessor.PROPERTY_KEY_SUFFIX_CHAR:
					if (unclosedPrefixes == 0) {
						// No unclosed prefix(es) in the property name (left) ->
						// this is the suffix we are looking for.
						return i;
					}
					else {
						// This suffix does not close the initial prefix but rather
						// just one that occurred within the property name.
						unclosedPrefixes--;
					}
					break;
			}
		}
		return -1;
	}


	@Override
	public String toString() {
		String className = getClass().getName();
		if (this.wrappedObject == null) {
			return className + ": no wrapped object set";
		}
		return className + ": wrapping object [" + ObjectUtils.identityToString(this.wrappedObject) + ']';
	}


	/**
	 * A handler for a specific property.
	 */
	protected abstract static class PropertyHandler {

		//属性类型
		private final Class<?> propertyType;

		//是否可读
		private final boolean readable;

		//是否可写
		private final boolean writable;

		public PropertyHandler(Class<?> propertyType, boolean readable, boolean writable) {
			this.propertyType = propertyType;
			this.readable = readable;
			this.writable = writable;
		}

		public Class<?> getPropertyType() {
			return this.propertyType;
		}

		public boolean isReadable() {
			return this.readable;
		}

		public boolean isWritable() {
			return this.writable;
		}

		public abstract TypeDescriptor toTypeDescriptor();

		public abstract ResolvableType getResolvableType();

		@Nullable
		public Class<?> getMapKeyType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(0);
		}

		@Nullable
		public Class<?> getMapValueType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asMap().resolveGeneric(1);
		}

		@Nullable
		public Class<?> getCollectionType(int nestingLevel) {
			return getResolvableType().getNested(nestingLevel).asCollection().resolveGeneric();
		}

		@Nullable
		public abstract TypeDescriptor nested(int level);

		@Nullable
		public abstract Object getValue() throws Exception;

		public abstract void setValue(@Nullable Object value) throws Exception;
	}


	/**
	 * Holder class used to store property tokens.
	 */
	protected static class PropertyTokenHolder {

		public PropertyTokenHolder(String name) {
			this.actualName = name;
			this.canonicalName = name;
		}

		//属性的真实名字
		public String actualName;

		//规范名称名
		public String canonicalName;

		//[]中的内容
		@Nullable
		public String[] keys;
	}

}
