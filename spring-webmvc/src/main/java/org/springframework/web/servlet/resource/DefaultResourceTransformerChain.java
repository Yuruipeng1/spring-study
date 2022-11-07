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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Default immutable implementation of {@link ResourceTransformerChain}.
 *
 * @author Rossen Stoyanchev
 * @since 4.1
 */
class DefaultResourceTransformerChain implements ResourceTransformerChain {

	//资源解析器链
	private final ResourceResolverChain resolverChain;

	//当前使用的资源转换器
	@Nullable
	private final ResourceTransformer transformer;

	//下一个资源转换器的应用
	@Nullable
	private final ResourceTransformerChain nextChain;


	//构造方法
	public DefaultResourceTransformerChain(
			ResourceResolverChain resolverChain, @Nullable List<ResourceTransformer> transformers) {

		Assert.notNull(resolverChain, "ResourceResolverChain is required");
		this.resolverChain = resolverChain;
		transformers = (transformers != null ? transformers : Collections.emptyList());
		//将资源转换器初始化为一个链条
		DefaultResourceTransformerChain chain = initTransformerChain(resolverChain, new ArrayList<>(transformers));
		this.transformer = chain.transformer;
		this.nextChain = chain.nextChain;
	}

	/**
	 * 资源转换器初始化为一个链条
	 * 其过程和初始化资源解析器链条差不多，都是反向遍历，然后得到一个正向的链条
	 */
	private DefaultResourceTransformerChain initTransformerChain(ResourceResolverChain resolverChain,
			ArrayList<ResourceTransformer> transformers) {

		DefaultResourceTransformerChain chain = new DefaultResourceTransformerChain(resolverChain, null, null);
		ListIterator<? extends ResourceTransformer> it = transformers.listIterator(transformers.size());
		while (it.hasPrevious()) {
			chain = new DefaultResourceTransformerChain(resolverChain, it.previous(), chain);
		}
		return chain;
	}

	public DefaultResourceTransformerChain(ResourceResolverChain resolverChain,
			@Nullable ResourceTransformer transformer, @Nullable ResourceTransformerChain chain) {

		Assert.isTrue((transformer == null && chain == null) || (transformer != null && chain != null),
				"Both transformer and transformer chain must be null, or neither is");

		this.resolverChain = resolverChain;
		this.transformer = transformer;
		this.nextChain = chain;
	}


	@Override
	public ResourceResolverChain getResolverChain() {
		return this.resolverChain;
	}

	//借助资源转换器进行转换
	@Override
	public Resource transform(HttpServletRequest request, Resource resource) throws IOException {
		return (this.transformer != null && this.nextChain != null ?
				this.transformer.transform(request, resource, this.nextChain) : resource);
	}

}
