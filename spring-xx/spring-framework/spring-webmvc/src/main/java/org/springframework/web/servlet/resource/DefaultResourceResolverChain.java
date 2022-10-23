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

package org.springframework.web.servlet.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Default immutable implementation of {@link ResourceResolverChain}.
 *
 * @author Rossen Stoyanchev
 * @since 4.1
 */
class DefaultResourceResolverChain implements ResourceResolverChain {

	//当前使用的资源解析器
	@Nullable
	private final ResourceResolver resolver;

	//下一个资源解析器所在位置
	@Nullable
	private final ResourceResolverChain nextChain;


	//构造方法
	public DefaultResourceResolverChain(@Nullable List<? extends ResourceResolver> resolvers) {
		resolvers = (resolvers != null ? resolvers : Collections.emptyList());
		DefaultResourceResolverChain chain = initChain(new ArrayList<>(resolvers));
		this.resolver = chain.resolver;
		this.nextChain = chain.nextChain;
	}

	//初始化链
	private static DefaultResourceResolverChain initChain(ArrayList<? extends ResourceResolver> resolvers) {
		//创建一个空的链
		DefaultResourceResolverChain chain = new DefaultResourceResolverChain(null, null);
		//得到这个list集合迭代器
		ListIterator<? extends ResourceResolver> it = resolvers.listIterator(resolvers.size());
		//反向遍历这个迭代器,将这些资源解析器构造为一个链条，典型的责任链模式
		while (it.hasPrevious()) {
			chain = new DefaultResourceResolverChain(it.previous(), chain);
		}
		return chain;
	}

	//构造方法
	private DefaultResourceResolverChain(@Nullable ResourceResolver resolver, @Nullable ResourceResolverChain chain) {
		Assert.isTrue((resolver == null && chain == null) || (resolver != null && chain != null),
				"Both resolver and resolver chain must be null, or neither is");
		this.resolver = resolver;
		this.nextChain = chain;
	}


	/******************************内部最终调用它持有的资源解析*************************/
	//接口方法，解析请求路径对应的资源
	@Override
	@Nullable
	public Resource resolveResource(
			@Nullable HttpServletRequest request, String requestPath, List<? extends Resource> locations) {

		return (this.resolver != null && this.nextChain != null ?
				this.resolver.resolveResource(request, requestPath, locations, this.nextChain) : null);
	}

	//接口方法，解析请求路径
	@Override
	@Nullable
	public String resolveUrlPath(String resourcePath, List<? extends Resource> locations) {
		return (this.resolver != null && this.nextChain != null ?
				this.resolver.resolveUrlPath(resourcePath, locations, this.nextChain) : null);
	}

}
