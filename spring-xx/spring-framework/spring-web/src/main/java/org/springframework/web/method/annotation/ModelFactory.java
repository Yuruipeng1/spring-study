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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.core.Conventions;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Assist with initialization of the {@link Model} before controller method
 * invocation and with updates to it after the invocation.
 *
 * <p>On initialization the model is populated with attributes temporarily stored
 * in the session and through the invocation of {@code @ModelAttribute} methods.
 *
 * <p>On update model attributes are synchronized with the session and also
 * {@link BindingResult} attributes are added if missing.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
//在控制器方法调用之前初始化Model，会将@ModelAttribute注解指定属性保存到Model中
//在控制器方法调用之后更新Model数据
public final class ModelFactory {

	//模型方法
	private final List<ModelMethod> modelMethods = new ArrayList<>();

	//数据绑定器工厂
	private final WebDataBinderFactory dataBinderFactory;

	//session属性处理器
	private final SessionAttributesHandler sessionAttributesHandler;


	/**
	 * Create a new instance with the given {@code @ModelAttribute} methods.
	 * @param handlerMethods the {@code @ModelAttribute} methods to invoke
	 * @param binderFactory for preparation of {@link BindingResult} attributes
	 * @param attributeHandler for access to session attributes
	 */
	public ModelFactory(@Nullable List<InvocableHandlerMethod> handlerMethods,
			WebDataBinderFactory binderFactory, SessionAttributesHandler attributeHandler) {

		if (handlerMethods != null) {
			for (InvocableHandlerMethod handlerMethod : handlerMethods) {
				this.modelMethods.add(new ModelMethod(handlerMethod));
			}
		}
		this.dataBinderFactory = binderFactory;
		this.sessionAttributesHandler = attributeHandler;
	}


	/**
	 * Populate the model in the following order:
	 * <ol>
	 * <li>Retrieve "known" session attributes listed as {@code @SessionAttributes}.
	 * <li>Invoke {@code @ModelAttribute} methods
	 * <li>Find {@code @ModelAttribute} method arguments also listed as
	 * {@code @SessionAttributes} and ensure they're present in the model raising
	 * an exception if necessary.
	 * </ol>
	 * @param request the current request
	 * @param container a container with the model to be initialized
	 * @param handlerMethod the method for which the model is initialized
	 * @throws Exception may arise from {@code @ModelAttribute} methods
	 */
	//初始化模型
	public void initModel(NativeWebRequest request, ModelAndViewContainer container, HandlerMethod handlerMethod)
			throws Exception {

		//得到@SessionAttributes注解声明的所有session域属性键值对
		Map<String, ?> sessionAttributes = this.sessionAttributesHandler.retrieveAttributes(request);
		/**
		 * 合并模型数据
		 * 合并request和session域属性
		 */
		container.mergeAttributes(sessionAttributes);
		//执行模型属性方法
		invokeModelAttributeMethods(request, container);

		//获取模型方法所有中Session属性参数名
		for (String name : findSessionAttributeArguments(handlerMethod)) {
			//模型视图容器中不包含该参数名
			if (!container.containsAttribute(name)) {
				//取得参数名对应的session属性值
				Object value = this.sessionAttributesHandler.retrieveAttribute(request, name);
				if (value == null) {
					throw new HttpSessionRequiredException("Expected session attribute '" + name + "'", name);
				}
				//保存到模型视图容器中
				container.addAttribute(name, value);
			}
		}
	}

	/**
	 * Invoke model attribute methods to populate the model.
	 * Attributes are added only if not already present in the model.
	 */
	//执行模型属性方法
	private void invokeModelAttributeMethods(NativeWebRequest request, ModelAndViewContainer container)
			throws Exception {

		//遍历执行所有@ModelAttribute注解方法（包括全局的）
		while (!this.modelMethods.isEmpty()) {
			//顺序获取模型方法
			InvocableHandlerMethod modelMethod = getNextModelMethod(container).getHandlerMethod();
			//得到方法上的@ModelAttribute注解信息
			ModelAttribute ann = modelMethod.getMethodAnnotation(ModelAttribute.class);
			Assert.state(ann != null, "No ModelAttribute annotation");
			//模型视图容器中包含用户在的@ModelAttribute注解name属性指定的名字
			if (container.containsAttribute(ann.name())) {
				//@ModelAttribute注解上声明关闭数据绑定
				if (!ann.binding()) {
					//@ModelAttribute注解上声明的所有属性都禁用数据绑定
					container.setBindingDisabled(ann.name());
				}
				continue;
			}

			//执行模型方法
			Object returnValue = modelMethod.invokeForRequest(request, container);
			//模型方法有返回值
			if (!modelMethod.isVoid()){
				//获取方法返回值的名字
				String returnValueName = getNameForReturnValue(returnValue, modelMethod.getReturnType());
				//该返回值禁用数据绑定
				if (!ann.binding()) {
					container.setBindingDisabled(returnValueName);
				}
				//返回值保存到模型中
				if (!container.containsAttribute(returnValueName)) {
					container.addAttribute(returnValueName, returnValue);
				}
			}
		}
	}

	private ModelMethod getNextModelMethod(ModelAndViewContainer container) {
		for (ModelMethod modelMethod : this.modelMethods) {
			/**
			 * checkDependencies(container)方法
			 * 会把模型方法参数上有@ModelAttribute注解的参数和视图模型容器中的属性一一比较
			 * 只要有一个标注了@ModelAttribute注解的参数在视图模型容器中找不到同名属性，就返回false
			 * 说明该模型方法还未执行过，需要执行.
			 * 否则返回true，从列表中移除该标注了@ModelAttribute注解的方法
			 */
			if (modelMethod.checkDependencies(container)) {
				this.modelMethods.remove(modelMethod);
				return modelMethod;
			}
		}
		//按顺序获取模型方法
		ModelMethod modelMethod = this.modelMethods.get(0);
		this.modelMethods.remove(modelMethod);
		return modelMethod;
	}

	/**
	 * Find {@code @ModelAttribute} arguments also listed as {@code @SessionAttributes}.
	 * 获取模型方法所有中Session属性参数名
	 */
	private List<String> findSessionAttributeArguments(HandlerMethod handlerMethod) {
		List<String> result = new ArrayList<>();
		//遍历模型方法参数
		for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
			//参数上有@ModelAttribute注解
			if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
				//参数名
				String name = getNameForParameter(parameter);
				//参数类型
				Class<?> paramType = parameter.getParameterType();
				//参数是@SessionAttributes注解指定的属性
				if (this.sessionAttributesHandler.isHandlerSessionAttribute(name, paramType)) {
					result.add(name);
				}
			}
		}
		return result;
	}

	/**
	 * Promote model attributes listed as {@code @SessionAttributes} to the session.
	 * Add {@link BindingResult} attributes where necessary.
	 * @param request the current request
	 * @param container contains the model to update
	 * @throws Exception if creating BindingResult attributes fails
	 */
	// 将@SessionAttributes注解声明的属性保存到session域
	public void updateModel(NativeWebRequest request, ModelAndViewContainer container) throws Exception {
		//得到默认模型
		ModelMap defaultModel = container.getDefaultModel();
		//当前会话已完成
		if (container.getSessionStatus().isComplete()){
			//清理@SessionAttributes注解声明的会话属性
			this.sessionAttributesHandler.cleanupAttributes(request);
		}
		//当前会话未完成
		else {
			//只会将模型中与用户通过@SessionAttributes注解声明的匹配的属性存储到session域中
			this.sessionAttributesHandler.storeAttributes(request, defaultModel);
		}
		//请求未被完全处理，且使用默认模型（即非重定向）
		if (!container.isRequestHandled() && container.getModel() == defaultModel) {
			//将需要数据绑定的属性添加到模型中
			updateBindingResult(request, defaultModel);
		}
	}

	/**
	 * Add {@link BindingResult} attributes to the model for attributes that require it.
	 */
	//将数据绑定的属性添加到模型中
	private void updateBindingResult(NativeWebRequest request, ModelMap model) throws Exception {
		List<String> keyNames = new ArrayList<>(model.keySet());
		for (String name : keyNames) {
			Object value = model.get(name);
			//该属性需要进行数据绑定
			if (value != null && isBindingCandidate(name, value)) {
				String bindingResultKey = BindingResult.MODEL_KEY_PREFIX + name;
				//模型中不包含该数据绑定属性
				if (!model.containsAttribute(bindingResultKey)) {
					//使用数据绑定器工厂创建该属性的数据绑定器
					WebDataBinder dataBinder = this.dataBinderFactory.createBinder(request, value, name);
					//保存属性
					model.put(bindingResultKey, dataBinder.getBindingResult());
				}
			}
		}
	}

	/**
	 * Whether the given attribute requires a {@link BindingResult} in the model.
	 */
	private boolean isBindingCandidate(String attributeName, Object value) {
		/**
		 * MODEL_KEY_PREFIX="org.springframework.validation.BindingResult."
		 * 以这个开头的属性不需要数据绑定
		 */
		if (attributeName.startsWith(BindingResult.MODEL_KEY_PREFIX)) {
			return false;
		}

		//用户通过@SessionAttributes注解指定的属性需要进行数据绑定
		if (this.sessionAttributesHandler.isHandlerSessionAttribute(attributeName, value.getClass())) {
			return true;
		}

		//非集合，非数组，非Map，非简单类型的属性都需要进行数据绑定
		return (!value.getClass().isArray() && !(value instanceof Collection) &&
				!(value instanceof Map) && !BeanUtils.isSimpleValueType(value.getClass()));
	}


	/**
	 * Derive the model attribute name for the given method parameter based on
	 * a {@code @ModelAttribute} parameter annotation (if present) or falling
	 * back on parameter type based conventions.
	 * @param parameter a descriptor for the method parameter
	 * @return the derived name
	 * @see Conventions#getVariableNameForParameter(MethodParameter)
	 */
	public static String getNameForParameter(MethodParameter parameter) {
		//@ModelAttribute注解的value属性值作为参数名
		ModelAttribute ann = parameter.getParameterAnnotation(ModelAttribute.class);
		String name = (ann != null ? ann.value() : null);
		//否则默认策略得到参数名
		return (StringUtils.hasText(name) ? name : Conventions.getVariableNameForParameter(parameter));
	}

	/**
	 * Derive the model attribute name for the given return value. Results will be
	 * based on:
	 * <ol>
	 * <li>the method {@code ModelAttribute} annotation value
	 * <li>the declared return type if it is more specific than {@code Object}
	 * <li>the actual return value type
	 * </ol>
	 * @param returnValue the value returned from a method invocation
	 * @param returnType a descriptor for the return type of the method
	 * @return the derived name (never {@code null} or empty String)
	 */
	/**
	 * 返回值保存在模型中的名字，有两种选择
	 *
	 * 用户可以在方法标注的@ModelAttribute注解上指定名字
	 * 系统自动根据返回值类型生成一个首字母小写的短类名作为名字
	 * @param returnValue
	 * @param returnType
	 * @return
	 */
	//获取方法返回值的名字
	public static String getNameForReturnValue(@Nullable Object returnValue, MethodParameter returnType) {
		//获取模型方法上的@ModelAttribute注解信息
		ModelAttribute ann = returnType.getMethodAnnotation(ModelAttribute.class);
		/**
		 * 有@ModelAttribute注解且指定了返回值的存储名
		 * 以@ModelAttribute注解的value属性为key，保存模型方法的返回到模型中
		 */
		if (ann != null && StringUtils.hasText(ann.value())) {
			return ann.value();
		}
		//使用默认的名字将返回值保存到模型中
		//根据返回值的完全限定名，生成一个首字母小写的短类名作为名字
		else {
			Method method = returnType.getMethod();
			Assert.state(method != null, "No handler method");
			//包含返回值的类的clazz对象
			Class<?> containingClass = returnType.getContainingClass();
			//方法声明的返回值的泛型类型
			Class<?> resolvedType = GenericTypeResolver.resolveReturnType(method, containingClass);
			//一般都是首字母小写的短类名
			return Conventions.getVariableNameForReturnType(method, resolvedType, returnValue);
		}
	}


	private static class ModelMethod {

		//模型属性方法
		private final InvocableHandlerMethod handlerMethod;

		//@ModelAttribute注解标注的参数名
		private final Set<String> dependencies = new HashSet<>();

		public ModelMethod(InvocableHandlerMethod handlerMethod) {
			this.handlerMethod = handlerMethod;
			/**
			 * 遍历模型属性方法的参数
			 * 参数上有@ModelAttribute注解就将参数名保存到dependencies属性中
			 */
			for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
				if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
					this.dependencies.add(getNameForParameter(parameter));
				}
			}
		}

		public InvocableHandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		//判断视图模型容器中是否存在@ModelAttribute注解标注的参数名
		public boolean checkDependencies(ModelAndViewContainer mavContainer) {
			for (String name : this.dependencies) {
				if (!mavContainer.containsAttribute(name)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public String toString() {
			return this.handlerMethod.getMethod().toGenericString();
		}
	}

}
