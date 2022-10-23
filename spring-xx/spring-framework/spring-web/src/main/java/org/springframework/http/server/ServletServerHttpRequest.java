/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.http.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.StringUtils;

/**
 * {@link ServerHttpRequest} implementation that is based on a {@link HttpServletRequest}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.0
 */

/**
 * HttpMessage接口：提供获取请求头的方法
 *
 *
 * HttpRequest接口：提供获取请求方式和请求URI的方法
 *
 *
 * HttpInputMessage接口：提供获取请求体的方法
 *
 *
 * ServerHttpRequest接口：提供4个方法
 * getPrincipal()：得到已认证用户的Principal实例
 * getLocalAddress()：获取接收请求的地址
 * getRemoteAddress()：获取发送请求的地址
 * getAsyncRequestControl()：返回一个异步请求控件，以便将该请求置于异步状态
 */
public class ServletServerHttpRequest implements ServerHttpRequest {

	protected static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

	protected static final Charset FORM_CHARSET = StandardCharsets.UTF_8;


	//原始的request对象
	private final HttpServletRequest servletRequest;

	@Nullable
	private URI uri;

	//请求头
	@Nullable
	private HttpHeaders headers;

	//异步请求控件，以便将该请求置于异步状态
	@Nullable
	private ServerHttpAsyncRequestControl asyncRequestControl;


	/**
	 * Construct a new instance of the ServletServerHttpRequest based on the
	 * given {@link HttpServletRequest}.
	 * @param servletRequest the servlet request
	 */
	public ServletServerHttpRequest(HttpServletRequest servletRequest) {
		Assert.notNull(servletRequest, "HttpServletRequest must not be null");
		this.servletRequest = servletRequest;
	}


	/**
	 * Returns the {@code HttpServletRequest} this object is based on.
	 */
	public HttpServletRequest getServletRequest() {
		return this.servletRequest;
	}

	@Override
	@Nullable
	public HttpMethod getMethod() {
		return HttpMethod.resolve(this.servletRequest.getMethod());
	}

	@Override
	public String getMethodValue() {
		return this.servletRequest.getMethod();
	}

	@Override
	public URI getURI() {
		if (this.uri == null) {
			String urlString = null;
			boolean hasQuery = false;
			try {
				StringBuffer url = this.servletRequest.getRequestURL();
				String query = this.servletRequest.getQueryString();
				hasQuery = StringUtils.hasText(query);
				if (hasQuery) {
					url.append('?').append(query);
				}
				urlString = url.toString();
				this.uri = new URI(urlString);
			}
			catch (URISyntaxException ex) {
				if (!hasQuery) {
					throw new IllegalStateException(
							"Could not resolve HttpServletRequest as URI: " + urlString, ex);
				}
				// Maybe a malformed query string... try plain request URL
				try {
					urlString = this.servletRequest.getRequestURL().toString();
					this.uri = new URI(urlString);
				}
				catch (URISyntaxException ex2) {
					throw new IllegalStateException(
							"Could not resolve HttpServletRequest as URI: " + urlString, ex2);
				}
			}
		}
		return this.uri;
	}

	@Override
	//获取请求头
	/**
	 * 获取请求中所有请求头的key和value，并将它们封装到一个HttpHeaders对象中
	 * 解析请求头中的Content-Type属性，将其封装为一个MediaType对象
	 * 解析请求的字符编码，将其封装为一个Charset对象，并保存到媒体类型参数parameters中，
	 */
	public HttpHeaders getHeaders() {
		//没有缓存
		if (this.headers == null) {
			this.headers = new HttpHeaders();

			/**
			 * 使用原始的request.getHeaderNames()方法得到所有请求头名字
			 * Enumeration类似于迭代器
			 */
			for (Enumeration<?> names = this.servletRequest.getHeaderNames(); names.hasMoreElements();) {
				//得到下一个请求头的名字
				String headerName = (String) names.nextElement();
				//获取名字对应的值，值可能有多个
				for (Enumeration<?> headerValues = this.servletRequest.getHeaders(headerName);
						headerValues.hasMoreElements();) {
					String headerValue = (String) headerValues.nextElement();
					//保存到springmvc定义的请求头类中
					this.headers.add(headerName, headerValue);
				}
			}

			// HttpServletRequest exposes some headers as properties:
			// we should include those if not already present
			try {
				/*****************************媒体类型**********************************/
				//获取媒体类型
				MediaType contentType = this.headers.getContentType();
				/**
				 * 上面获取请求头的媒体类型，如果获取不到，就调用原始的request.getContentType()
				 * 方法，得到内容类型，最后通过MediaType.parseMediaType(requestContentType)方法
				 * 将内容类型转化为媒体类型,最后再保存到请求头中
				 */
				if (contentType == null) {
					String requestContentType = this.servletRequest.getContentType();
					if (StringUtils.hasLength(requestContentType)) {
						//将String转化为MediaType
						contentType = MediaType.parseMediaType(requestContentType);

						//设置媒体类型
						this.headers.setContentType(contentType);
					}
				}
				/*****************************字符编码**********************************/
				if (contentType != null && contentType.getCharset() == null) {
					//原始的request.getCharacterEncoding()方法获取字符编码
					String requestEncoding = this.servletRequest.getCharacterEncoding();
					//设置了字符编码
					if (StringUtils.hasLength(requestEncoding)) {
						//String类型转换为Charset
						Charset charSet = Charset.forName(requestEncoding);
						Map<String, String> params = new LinkedCaseInsensitiveMap<>();
						//复制原来所有参数，并添加新的字符编码参数
						params.putAll(contentType.getParameters());
						params.put("charset", charSet.toString());
						//重新构建一个媒体类型对象，保存到请求头中
						MediaType mediaType = new MediaType(contentType.getType(), contentType.getSubtype(), params);
						this.headers.setContentType(mediaType);
					}
				}
			}
			catch (InvalidMediaTypeException ex) {
				// Ignore: simply not exposing an invalid content type in HttpHeaders...
			}

			//修改请求头内容长度
			if (this.headers.getContentLength() < 0) {
				int requestContentLength = this.servletRequest.getContentLength();
				if (requestContentLength != -1) {
					this.headers.setContentLength(requestContentLength);
				}
			}
		}

		return this.headers;
	}

	@Override
	public Principal getPrincipal() {
		return this.servletRequest.getUserPrincipal();
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		return new InetSocketAddress(this.servletRequest.getLocalAddr(), this.servletRequest.getLocalPort());
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		return new InetSocketAddress(this.servletRequest.getRemoteHost(), this.servletRequest.getRemotePort());
	}

	@Override
	public InputStream getBody() throws IOException {
		//该请求是表单提交的post请求
		if (isFormPost(this.servletRequest)) {
			//得到表单提交的post请求的输入流
			return getBodyFromServletRequestParameters(this.servletRequest);
		}
		//非表单提交，直接使用原始的request.getInputStream()方法获取请求体输入流
		else {
			return this.servletRequest.getInputStream();
		}
	}

	@Override
	public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
		if (this.asyncRequestControl == null) {
			if (!(response instanceof ServletServerHttpResponse)) {
				throw new IllegalArgumentException(
						"Response must be a ServletServerHttpResponse: " + response.getClass());
			}
			ServletServerHttpResponse servletServerResponse = (ServletServerHttpResponse) response;
			this.asyncRequestControl = new ServletServerHttpAsyncRequestControl(this, servletServerResponse);
		}
		return this.asyncRequestControl;
	}


	//判断请求是不是表单提交的POST请求
	private static boolean isFormPost(HttpServletRequest request) {
		//获取内容类型
		String contentType = request.getContentType();
		/**
		 * FORM_CONTENT_TYPE="application/x-www-form-urlencoded"
		 * 代表表单类型内容
		 */
		return (contentType != null && contentType.contains(FORM_CONTENT_TYPE) &&
				HttpMethod.POST.matches(request.getMethod()));
	}

	/**
	 * Use {@link javax.servlet.ServletRequest#getParameterMap()} to reconstruct the
	 * body of a form 'POST' providing a predictable outcome as opposed to reading
	 * from the body, which can fail if any other code has used the ServletRequest
	 * to access a parameter, thus causing the input stream to be "consumed".
	 */
	//得到表单提交的post请求的请求体输入流
	private static InputStream getBodyFromServletRequestParameters(HttpServletRequest request) throws IOException {
		//字节数组输出流
		ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
		//使用转换流转换为字符流
		Writer writer = new OutputStreamWriter(bos, FORM_CHARSET);

		//得到所有的请求参数
		Map<String, String[]> form = request.getParameterMap();
		//使用字符流的write()方法将请求参数写入字符流中
		for (Iterator<String> nameIterator = form.keySet().iterator(); nameIterator.hasNext();) {
			String name = nameIterator.next();
			List<String> values = Arrays.asList(form.get(name));
			for (Iterator<String> valueIterator = values.iterator(); valueIterator.hasNext();) {
				String value = valueIterator.next();
				writer.write(URLEncoder.encode(name, FORM_CHARSET.name()));
				if (value != null) {
					writer.write('=');
					writer.write(URLEncoder.encode(value, FORM_CHARSET.name()));
					if (valueIterator.hasNext()) {
						writer.write('&');
					}
				}
			}
			if (nameIterator.hasNext()) {
				writer.append('&');
			}
		}
		writer.flush();

		//构建一个输入流
		return new ByteArrayInputStream(bos.toByteArray());
	}

}
