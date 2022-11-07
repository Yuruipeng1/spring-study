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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	/**
	 * invokeBeanFactoryPostProcessor--------- 执行BeanFactoryPostProcessor所有实现类
	 * 所有实现类 {
	 *     1.spring内置的---在这个方法之前被封装成bd 并且put到了bdmap中
	 *     2.程序员提供的--- 一种是通过扫描出来的(被注解标注)，
	 *     					另一种是通过api提供(就是beanFactoryPostProcessors参数中的)
	 *
	 * }
	 *
	 * beanFactoryPostProcessors一般为null
	 * 只有在程序员通过spring容器对象手动添加BeanFactoryPostProcessor对象才会存在这个集合
	 *
	 * //先执行子类BeanDefinitionRegistryPostProcessor(扫描)--先执行内部，再执行程序员自己的
	 * (1)先执行spring内部----用的是自己把一个BeanDefinitionRegistryPostProcessor的实现类ConfigurationClassPostProcessor
	 * 的bd放到map当中，想要执行先得实例化--先从容器中拿，如果有直接执行，如果没有spring传一个先执行子类BeanDefinitionRegistryPostProcessor
	 * 类型给容器，容器就会从map当中找----找到一个ConfigurationClassPostProcessor
	 * (2)然后就可以通过ConfigurationClassPostProcessor扫描，新加入程序员提供的，再执行程序员提供的
	 *
	 * //再执行BeanFactoryPostProcessor（扫描后）--先执行内部，再执行程序员自己的
	 * @param beanFactory
	 * @param beanFactoryPostProcessors
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		//所有已经找出来的（已经处理了，已经执行完的） BeanFactoryPostProcessor 或者 BeanDefinitionRegistryPostProcessor
		//仅仅存放名字，保证不会重复执行
		Set<String> processedBeans = new HashSet<>();

		// 如果是BeanDefinitionRegistry
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			/**
			 * 存放所有找出来的 BeanFactoryPostProcessor  父类
			 */
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			/**
			 * 存放所有找出来的 BeanDefinitionRegistryPostProcessor  子类的
			 */
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			// BeanDefinitionRegistryPostProcessor先处理，保存在registryProcessors
			//为什么最先执行通过api提供的？
			//为了让程序员提供的能够在扫描前先执行
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					//如果是强转后直接执行
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					//执行完成后存放registryProcessors
					//为什么执行完成了之后还要存放到这个list当中呢？
					//主要是因为这里是判断子类，虽然子类的回调方法执行完成了
					//但是父类的回调方法没有执行，存放到这个list当中就是为了
					//后续到了执行父类回调的时机得时候，去遍历这个list，然后依次获取出来执行
					registryProcessors.add(registryProcessor);
				}
				else {
					/**
					 * 为什么这里不执行而是缓存起来
					 * 因为他们的执行时机不能乱（要保证先执行BeanDefinitionRegistryPostProcessor
					 * 再执行BeanFactoryPostProcessor
					 * ）
					 */
					// 保存常规的，非BeanDefinitionRegistryPostProcessor
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			//定义了一个集合来存放当前需要执行的 BeanDefinitionRegistryPostProcessor 为什么需要这个集合
			//因为在spring的代码角度考虑BeanDefinitionRegistryPostProcessor的种类很多
			//主要三种
			//1、实现了 PriorityOrdered接口类型的
			//2、实现了 Ordered 接口类型的
			//3、什么都没实现
			//站在BeanDefinitionRegistryPostProcessor的来源角度来考虑分为两种
			//1、spring自己提供的
			//2、外部提供的
			//由于 BeanDefinitionRegistryPostProcessor种类很复杂，故而spring得分批执行
			//这样能保证这些不同类型得BeanDefinitionRegistryPostProcessor 得执行时机
			//所以每次找到合适得，就需要一个集合来存放，然后执行
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			/**
			 * 获取BeanDefinitionRegistryPostProcessor类型的beanName
			 * 此时bdmap中只有spring内置的BeanDefinitionRegistryPostProcessor实现类
			 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 先执行实现了PriorityOrdered接口的bean
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 生成的bean保存在currentRegistryProcessors
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 保存被处理过的beanName
					processedBeans.add(ppName);
				}
			}
			// 对currentRegistryProcessors进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 全部添加到registryProcessors，此时所有的BeanFactoryPostProcessor，包括定义的bean，都保存在registryProcessors
			registryProcessors.addAll(currentRegistryProcessors);
			// 获取PriorityOrdered接口的BeanFactoryPostProcessor完后，进行后置处理
			/**
			 * 在这一句代码中完成了ConfigurationClassPostProcessor的扫描工作
			 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			// 清空currentRegistryProcessors作其他用途
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			// 获取BeanDefinitionRegistryPostProcessor，且实现Ordered接口的。
			// 实现了PriorityOrdered接口的，通过processedBeans排除了
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					// 生成的bean保存在currentRegistryProcessors
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 保存被处理过的beanName
					processedBeans.add(ppName);
				}
			}
			// 对currentRegistryProcessors进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 全部添加到registryProcessors,方便执行父类的回调方法
			registryProcessors.addAll(currentRegistryProcessors);
			// 获取Ordered接口的BeanFactoryPostProcessor完后，进行后置处理
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			// 清空currentRegistryProcessors作其他用途
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 为false说明已经遍历完了
			boolean reiterate = true;

			//执行所有没有实现PriorityOrdered 和Ordered接口的 (也有可能会执行实现了这些接口的，因为执行这些方法操作可能会新加)
			while (reiterate) {
				reiterate = false;
				// 获取processedBeans排除的BeanDefinitionRegistryPostProcessor
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						// 如果能够找到，说明可能会往容器中添加新的bd
						reiterate = true;
					}
				}
				// 对currentRegistryProcessors进行排序
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				// 全部添加到registryProcessors
				registryProcessors.addAll(currentRegistryProcessors);
				// 获取的BeanFactoryPostProcessor完后，进行后置处理
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				// 清空currentRegistryProcessors作其他用途
				currentRegistryProcessors.clear();
			}

			/**
			 * 上面代码是执行BeanFactoryPostProcessor的子类 BeanDefinitionRegistryPostProcessor
			 * 的所有实现类的postProcessBeanDefinitionRegistry
			 *
			 * (如果程序员通过api 容器对象直接注册了BeanDefinitionRegistryPostProcessor则先执行这个api中的)
			 * 先执行spring内置的---------->ConfigurationClassPostProcessor
			 * 然后通过ConfigurationClassPostProcessor去完成扫描  程序员通过注解、xml。。。。提供的
			 * BeanDefinitionRegistryPostProcessor的所有实现类并且先执行PriorityOrdered接口的
			 * 再执行实现了Ordered接口的
			 * 然后再执行没有实现Ordered接口的
			 */


			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// BeanDefinitionRegistryPostProcessor处理完了，再调用BeanFactoryPostProcessor的后置处理器。
			//执行 直接实现了BeanFactoryPostProcessor类的对象
			/**
			 * registryProcessors 存储的是已经处理完的BeanDefinitionRegistryPostProcessor
			 * 因为一个类一旦实现了BeanDefinitionRegistryPostProcessor 那么他一定是一个BeanFactoryPostProcessor
			 * 最先执行的是实现了BeanDefinitionRegistryPostProcessor 的BeanFactoryPostProcessor
			 * 再执行直接实现了BeanFactoryPostProcessor的实现类
			 */
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			//这一句只是执行api提供的BeanFactoryPostProcessor
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			// 直接调用BeanFactoryPostProcessor的后置处理器。
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 上面主要对BeanDefinitionRegistryPostProcessor和传入的参数beanFactoryPostProcessors两种后置处理器的调用
		// 下面是bean定义中BeanFactoryPostProcessor后置处理器的调用
		// 获取bean定义中BeanFactoryPostProcessor
		/**
		 * 拿到所有的BeanFactoryPostProcessor的名字 包含内置的和程序员通过注解提供的
		 */
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.

		//为什么对于PriorityOrdered接口的要先实例化?
		//执行BeanFactoryPostProcessor有可能去修改其他的bd

		/**
		 * 存储BeanFactoryPostProcessor 实现了PriorityOrdered接口的 对象
		 */
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		/**
		 * 存储BeanFactoryPostProcessor 实现了Ordered接口的 名字
		 */
		List<String> orderedPostProcessorNames = new ArrayList<>();
		/**
		 * 存储BeanFactoryPostProcessor 没有实现了Ordered接口的 名字
		 */
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();

		for (String ppName : postProcessorNames) {
			// 已处理过的跳过
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				// 实现了PriorityOrdered接口的
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				// 实现了Ordered接口的
				orderedPostProcessorNames.add(ppName);
			}
			else {
				//其他的
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		// 先对实现了PriorityOrdered接口的排序，然后再调用后置处理器
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		// 再对实现了Ordered接口的排序，然后再调用后置处理器
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		// 最后调用剩下的后置处理器
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		// 因为后处理器可能有修改了原始的元数据，所以要清除缓存的合并bd,下次获取要重新合并
		beanFactory.clearMetadataCache();
	}

	//执行到这里的时候，spring已经完成了扫描
	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// 从bdmap中获取所有的BeanPostProcessor的名字,但这些BeanPostProcessor并不是所有的都实例化好了--单例池中
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		// 获取后置处理器的个数，这个+1就是加BeanPostProcessorChecker
		//正常走完生命周期流程需要经历几次BeanPostProcessor，这个值不会更改了
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		//BeanPostProcessorChecker 检查每个bean期望执行的后置处理器次数和 现在拿到的所有后置处理器的数量是否一致
		//用来给程序员排除错误
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 下面这段代码是不是很熟悉，跟BeanFactoryPostProcessor一样的思想，先PriorityOrdered再Ordered最后其他
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				// 保存实现了PriorityOrdered接口的BeanPostProcessor
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					// 保存实现了PriorityOrdered及MergedBeanDefinitionPostProcessor接口的BeanPostProcessor
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				// 保存实现了Ordered接口的BeanPostProcessor
				orderedPostProcessorNames.add(ppName);
			}
			else {
				// 保存其他接口的BeanPostProcessor
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		// PriorityOrdered接口，排序，注册到beanFactory
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		// Ordered接口，排序，注册到beanFactory，另外过滤MergedBeanDefinitionPostProcessor类型的加入到internalPostProcessors
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		// 其他接口，注册到beanFactory，另外过滤MergedBeanDefinitionPostProcessor类型的加入到internalPostProcessors
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		// MergedBeanDefinitionPostProcessor接口，排序，注册到beanFactory
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		// 添加ApplicationListenerDetector监听
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
