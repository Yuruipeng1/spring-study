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

package org.springframework.beans;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import org.springframework.core.ResolvableType;
import org.springframework.core.convert.Property;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * Default {@link BeanWrapper} implementation that should be sufficient
 * for all typical use cases. Caches introspection results for efficiency.
 *
 * <p>Note: Auto-registers default property editors from the
 * {@code org.springframework.beans.propertyeditors} package, which apply
 * in addition to the JDK's standard PropertyEditors. Applications can call
 * the {@link #registerCustomEditor(Class, java.beans.PropertyEditor)} method
 * to register an editor for a particular instance (i.e. they are not shared
 * across the application). See the base class
 * {@link PropertyEditorRegistrySupport} for details.
 *
 * <p><b>NOTE: As of Spring 2.5, this is - for almost all purposes - an
 * internal class.</b> It is just public in order to allow for access from
 * other framework packages. For standard application access purposes, use the
 * {@link PropertyAccessorFactory#forBeanPropertyAccess} factory method instead.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Stephane Nicoll
 * @since 15 April 2001
 * @see #registerCustomEditor
 * @see #setPropertyValues
 * @see #setPropertyValue
 * @see #getPropertyValue
 * @see #getPropertyType
 * @see BeanWrapper
 * @see PropertyEditorRegistrySupport
 */
public class BeanWrapperImpl extends AbstractNestablePropertyAccessor implements BeanWrapper {

	/**
	 * 内省工具类，提供了很多支持内省的方法
	 * 并缓存内省结果
	 */
	@Nullable
	private CachedIntrospectionResults cachedIntrospectionResults;

	/**
	 * The security context used for invoking the property methods.
	 */
	/**
	 * 安全上下文
	 */
	@Nullable
	private AccessControlContext acc;


	/**
	 * Create a new empty BeanWrapperImpl. Wrapped instance needs to be set afterwards.
	 * Registers default editors.
	 * @see #setWrappedInstance
	 */
	public BeanWrapperImpl() {
		this(true);
	}

	/**
	 * Create a new empty BeanWrapperImpl. Wrapped instance needs to be set afterwards.
	 * @param registerDefaultEditors whether to register default editors
	 * (can be suppressed if the BeanWrapper won't need any type conversion)
	 * @see #setWrappedInstance
	 */
	public BeanWrapperImpl(boolean registerDefaultEditors) {
		super(registerDefaultEditors);
	}

	/**
	 * Create a new BeanWrapperImpl for the given object.
	 * @param object the object wrapped by this BeanWrapper
	 */
	public BeanWrapperImpl(Object object) {
		super(object);
	}

	/**
	 * Create a new BeanWrapperImpl, wrapping a new instance of the specified class.
	 * @param clazz class to instantiate and wrap
	 */
	public BeanWrapperImpl(Class<?> clazz) {
		super(clazz);
	}

	/**
	 * Create a new BeanWrapperImpl for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this BeanWrapper
	 * @param nestedPath the nested path of the object
	 * @param rootObject the root object at the top of the path
	 */
	public BeanWrapperImpl(Object object, String nestedPath, Object rootObject) {
		super(object, nestedPath, rootObject);
	}

	/**
	 * Create a new BeanWrapperImpl for the given object,
	 * registering a nested path that the object is in.
	 * @param object the object wrapped by this BeanWrapper
	 * @param nestedPath the nested path of the object
	 * @param parent the containing BeanWrapper (must not be {@code null})
	 */
	private BeanWrapperImpl(Object object, String nestedPath, BeanWrapperImpl parent) {
		super(object, nestedPath, parent);
		setSecurityContext(parent.acc);
	}


	/**
	 * Set a bean instance to hold, without any unwrapping of {@link java.util.Optional}.
	 * @param object the actual target object
	 * @since 4.3
	 * @see #setWrappedInstance(Object)
	 */
	public void setBeanInstance(Object object) {
		this.wrappedObject = object;
		this.rootObject = object;
		this.typeConverterDelegate = new TypeConverterDelegate(this, this.wrappedObject);
		setIntrospectionClass(object.getClass());
	}

	@Override
	public void setWrappedInstance(Object object, @Nullable String nestedPath, @Nullable Object rootObject) {
		super.setWrappedInstance(object, nestedPath, rootObject);
		//重置内省的缓存
		setIntrospectionClass(getWrappedClass());
	}

	/**
	 * Set the class to introspect.
	 * Needs to be called when the target object changes.
	 * @param clazz the class to introspect
	 */
	protected void setIntrospectionClass(Class<?> clazz) {
		if (this.cachedIntrospectionResults != null && this.cachedIntrospectionResults.getBeanClass() != clazz) {
			this.cachedIntrospectionResults = null;
		}
	}

	/**
	 * Obtain a lazily initialized CachedIntrospectionResults instance
	 * for the wrapped object.
	 */
	/**
	 * 通过spring封装的内省工具类cachedIntrospectionResults的静态方法forClass(beanClass)，
	 * 得到所有属性描述PropertyDescriptor，然后获取指定属性的属性描述，封装为属性处理器BeanPropertyHandler
	 * @return
	 */

	private CachedIntrospectionResults getCachedIntrospectionResults() {
		if (this.cachedIntrospectionResults == null) {
			//内省，获取javabean对象属性信息
			this.cachedIntrospectionResults = CachedIntrospectionResults.forClass(getWrappedClass());
		}
		return this.cachedIntrospectionResults;
	}

	/**
	 * Set the security context used during the invocation of the wrapped instance methods.
	 * Can be null.
	 */
	public void setSecurityContext(@Nullable AccessControlContext acc) {
		this.acc = acc;
	}

	/**
	 * Return the security context used during the invocation of the wrapped instance methods.
	 * Can be null.
	 */
	@Nullable
	public AccessControlContext getSecurityContext() {
		return this.acc;
	}


	/**
	 * Convert the given value for the specified property to the latter's type.
	 * <p>This method is only intended for optimizations in a BeanFactory.
	 * Use the {@code convertIfNecessary} methods for programmatic conversion.
	 * @param value the value to convert
	 * @param propertyName the target property
	 * (note that nested or indexed properties are not supported here)
	 * @return the new value, possibly the result of type conversion
	 * @throws TypeMismatchException if type conversion failed
	 */
	@Nullable
	public Object convertForProperty(@Nullable Object value, String propertyName) throws TypeMismatchException {
		/**
		 * 获取当前缓存的bean的内省结果
		 * CachedIntrospectionResults是BeanWrapperImpl的一个字段，4中调用isWritableProperty()
		 * 方法时使用了内省机制，得到了当前bean的内省结果，并保存到了BeanWrapperImpl对象中
		 * 此处只是简单的将字段值取出
		 * 有关内省的方法源码都在下篇文章
		 */
		CachedIntrospectionResults cachedIntrospectionResults = getCachedIntrospectionResults();
		/**
		 * 获取propertyName的PropertyDescriptor属性描述
		 * 有关内省的方法源码都在下篇文章
		 */
		PropertyDescriptor pd = cachedIntrospectionResults.getPropertyDescriptor(propertyName);
		if (pd == null) {
			throw new InvalidPropertyException(getRootClass(), getNestedPath() + propertyName,
					"No property '" + propertyName + "' found");
		}
		/**
		 * 获取当前缓存的当前bean的指定属性的TypeDescriptor类型描述
		 * 后面根据这个TypeDescriptor进行类型转换
		 * 有关内省的方法源码都在下篇文章
		 */
		TypeDescriptor td = cachedIntrospectionResults.getTypeDescriptor(pd);
		/**
		 * 第一次肯定是不存在的，需要手动创建一个对应的TypeDescriptor，加入到缓存中
		 * 构建对应属性的TypeDescriptor要转换的类型描述，见4.2.1
		 */
		if (td == null) {
			td = cachedIntrospectionResults.addTypeDescriptor(pd, new TypeDescriptor(property(pd)));
		}
		//依据TypeDescriptor进行类型转换，见4.2.2
		return convertForProperty(propertyName, null, value, td);
	}

	private Property property(PropertyDescriptor pd) {
		/**
		 * spring内省forClass()方法得到的PropertyDescriptor就是
		 * GenericTypeAwarePropertyDescriptor类型的，所以这里可以直接强转
		 */
		GenericTypeAwarePropertyDescriptor gpd = (GenericTypeAwarePropertyDescriptor) pd;
		//创建一个Property对象，通过它来构建对应属性的TypeDescriptor
		return new Property(gpd.getBeanClass(), gpd.getReadMethod(), gpd.getWriteMethod(), gpd.getName());
	}


	@Override
	@Nullable
	protected BeanPropertyHandler getLocalPropertyHandler(String propertyName) {
		//获取属性名对应的PropertyDescriptor
		PropertyDescriptor pd = getCachedIntrospectionResults().getPropertyDescriptor(propertyName);
		//创建一个BeanPropertyHandler属性处理器
		return (pd != null ? new BeanPropertyHandler(pd) : null);
	}

	/**
	 * 创建外部属性对象的BeanWrapperImpl对象
	 * 保存了父BeanWrapperImpl对象
	 */
	@Override
	protected BeanWrapperImpl newNestedPropertyAccessor(Object object, String nestedPath) {
		return new BeanWrapperImpl(object, nestedPath, this);
	}

	@Override
	protected NotWritablePropertyException createNotWritablePropertyException(String propertyName) {
		PropertyMatches matches = PropertyMatches.forProperty(propertyName, getRootClass());
		throw new NotWritablePropertyException(getRootClass(), getNestedPath() + propertyName,
				matches.buildErrorMessage(), matches.getPossibleMatches());
	}

	@Override
	public PropertyDescriptor[] getPropertyDescriptors() {
		return getCachedIntrospectionResults().getPropertyDescriptors();
	}

	@Override
	public PropertyDescriptor getPropertyDescriptor(String propertyName) throws InvalidPropertyException {
		BeanWrapperImpl nestedBw = (BeanWrapperImpl) getPropertyAccessorForPropertyPath(propertyName);
		String finalPath = getFinalPath(nestedBw, propertyName);
		PropertyDescriptor pd = nestedBw.getCachedIntrospectionResults().getPropertyDescriptor(finalPath);
		if (pd == null) {
			throw new InvalidPropertyException(getRootClass(), getNestedPath() + propertyName,
					"No property '" + propertyName + "' found");
		}
		return pd;
	}


	private class BeanPropertyHandler extends PropertyHandler {

		//属性描述
		private final PropertyDescriptor pd;

		//PropertyDescriptor中保存了属性所有信息，快速初始化一个属性处理器
		public BeanPropertyHandler(PropertyDescriptor pd) {
			super(pd.getPropertyType(), pd.getReadMethod() != null, pd.getWriteMethod() != null);
			this.pd = pd;
		}

		@Override
		public ResolvableType getResolvableType() {
			//解析getter方法的返回值
			return ResolvableType.forMethodReturnType(this.pd.getReadMethod());
		}

		@Override
		/**
		 * 获取当前属性的类型描述
		 */
		public TypeDescriptor toTypeDescriptor() {
			return new TypeDescriptor(property(this.pd));
		}

		//嵌套属性的类型描述
		@Override
		@Nullable
		public TypeDescriptor nested(int level) {
			return TypeDescriptor.nested(property(this.pd), level);
		}

		/**
		 * 获取属性值
		 * 实际上就是通过反射调用getter方法得到属性值
		 */
		@Override
		@Nullable
		public Object getValue() throws Exception {
			Method readMethod = this.pd.getReadMethod();
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					ReflectionUtils.makeAccessible(readMethod);
					return null;
				});
				try {
					return AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
							() -> readMethod.invoke(getWrappedInstance(), (Object[]) null), acc);
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				ReflectionUtils.makeAccessible(readMethod);
				return readMethod.invoke(getWrappedInstance(), (Object[]) null);
			}
		}

		/**
		 * 设置属性值
		 * 实际上就是通过反射调用setter方法设置属性值
		 */
		@Override
		public void setValue(@Nullable Object value) throws Exception {
			Method writeMethod = (this.pd instanceof GenericTypeAwarePropertyDescriptor ?
					((GenericTypeAwarePropertyDescriptor) this.pd).getWriteMethodForActualAccess() :
					this.pd.getWriteMethod());
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					ReflectionUtils.makeAccessible(writeMethod);
					return null;
				});
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
							() -> writeMethod.invoke(getWrappedInstance(), value), acc);
				}
				catch (PrivilegedActionException ex) {
					throw ex.getException();
				}
			}
			else {
				ReflectionUtils.makeAccessible(writeMethod);
				writeMethod.invoke(getWrappedInstance(), value);
			}
		}
	}

}
