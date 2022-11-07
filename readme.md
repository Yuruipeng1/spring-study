```
invokeBeanFactoryPostProcessors原理
```

其中最重要的是invokeBeanFactoryPostProcessors方法，先上代码（在代码中已经写了详细的注释）

```java
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
```

总结：这个方法会执行BeanFactoryPostProcessor这种后置处理器，这个这个处理器有两种类型，一种是直接实现BeanFactoryPostProcessor接口的，还有一种是实现BeanDefinitionRegistryPostProcessor接口的（间接实现BeanFactoryPostProcessor），spring在执行时，先执行BeanDefinitionRegistryPostProcessor这种类型的，再执行BeanFactoryPostProcessor。

对于BeanDefinitionRegistryPostProcessor

(如果程序员通过api 容器对象直接注册了BeanDefinitionRegistryPostProcessor则先执行这个api中的)
 先执行spring内部----用的是自己把一个BeanDefinitionRegistryPostProcessor的实现类 ConfigurationClassPostProcessor的bd放到map当中，想要执行先得实例化--先从容器中拿，如果有直接执行，如果没有spring传一个先执行子类BeanDefinitionRegistryPostProcessor类型给容器，容器就会从map当中找----找到一个ConfigurationClassPostProcessor
 然后通过ConfigurationClassPostProcessor去完成扫描  程序员通过注解、xml。。。。提供的
 BeanDefinitionRegistryPostProcessor的所有实现类并且先执行PriorityOrdered接口的
 再执行实现了Ordered接口的
 然后再执行没有实现Ordered接口的



对于BeanFactoryPostProcessor执行顺序类似，主要有两个问题

1.为什么对于PriorityOrdered接口的要先实例化?

因为执行BeanFactoryPostProcessor有可能去修改其他的bd

2.关于Ordered执行顺序的动态性（因为在执行的时候可能产生新的BeanFactoryPostProcessor的实现类）

BeanDefinitionRegistryPostProcessor执行顺序是 先
1.priorityOrdered
2.ordered
3.non

spring的原则是保证pordered最先执行 (但是只是找的这一次能保证，整体不能保证)

第一次执行子类的时候拿到是所有实现了pordered接口的

第二次执行的时候 拿到ordered接口的和可能新产生的pordered接口的  因为做了sort，所有也能保证

第三次 如果没有新产生的pordered那么顺序也不会乱 但如果新产生了pordered那么和前面的顺序乱了