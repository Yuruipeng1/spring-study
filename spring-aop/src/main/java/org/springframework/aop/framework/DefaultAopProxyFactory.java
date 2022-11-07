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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Proxy;

import org.springframework.aop.SpringProxy;

/**
 * Default {@link AopProxyFactory} implementation, creating either a CGLIB proxy
 * or a JDK dynamic proxy.
 *
 * <p>Creates a CGLIB proxy if one the following is true for a given
 * {@link AdvisedSupport} instance:
 * <ul>
 * <li>the {@code optimize} flag is set
 * <li>the {@code proxyTargetClass} flag is set
 * <li>no proxy interfaces have been specified
 * </ul>
 *
 * <p>In general, specify {@code proxyTargetClass} to enforce a CGLIB proxy,
 * or specify one or more interfaces to use a JDK dynamic proxy.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 12.03.2004
 * @see AdvisedSupport#setOptimize
 * @see AdvisedSupport#setProxyTargetClass
 * @see AdvisedSupport#setInterfaces
 */
public class DefaultAopProxyFactory implements AopProxyFactory, Serializable {

	private static final long serialVersionUID = 7930414337282325166L;


	//创建AOP对象的真正实例
	@Override
	public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
		// 1.判断使用JDK动态代理还是Cglib代理
		// optimize：用于控制通过cglib创建的代理是否使用激进的优化策略。除非完全了解AOP如何处理代理优化，
		// 否则不推荐使用这个配置，目前这个属性仅用于cglib代理，对jdk动态代理无效
		// proxyTargetClass：默认为false，设置为true时，强制使用cglib代理，设置方式：<aop:aspectj-autoproxy proxy-target-class="true" />
		// hasNoUserSuppliedProxyInterfaces：config是否存在代理接口或者只有SpringProxy一个接口
		if (config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config)) {
			// 拿到要被代理的对象的类型
			Class<?> targetClass = config.getTargetClass();
			if (targetClass == null) {
				throw new AopConfigException("TargetSource cannot determine target class: " +
						"Either an interface or a target is required for proxy creation.");
			}
			// 要被代理的对象是接口 || targetClass是Proxy class
			// 当且仅当使用getProxyClass方法或newProxyInstance方法动态生成指定的类作为代理类时，才返回true。
			if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)) {
				// JDK动态代理，这边的入参config(AdvisedSupport)实际上是ProxyFactory对象
				// 具体为：AbstractAutoProxyCreator中的proxyFactory.getProxy发起的调用，在ProxyCreatorSupport使用了this作为参数，
				// 调用了的本方法，这边的this就是发起调用的proxyFactory对象，而proxyFactory对象中包含了要执行的的拦截器
				return new JdkDynamicAopProxy(config);
			}
			// Cglib代理
			return new ObjenesisCglibAopProxy(config);
		}
		else {
			// JDK动态代理
			return new JdkDynamicAopProxy(config);
		}
	}

	/**
	 * Determine whether the supplied {@link AdvisedSupport} has only the
	 * {@link org.springframework.aop.SpringProxy} interface specified
	 * (or no proxy interfaces specified at all).
	 */
	private boolean hasNoUserSuppliedProxyInterfaces(AdvisedSupport config) {
		Class<?>[] ifcs = config.getProxiedInterfaces();
		return (ifcs.length == 0 || (ifcs.length == 1 && SpringProxy.class.isAssignableFrom(ifcs[0])));
	}

}
