# 1.invokeBeanFactoryPostProcessors原理

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



# 2.推断构造方法原理

spring推断构造方法有两次推断，第一次是在SmartInstantiationAwareBeanPostProcessor接口中的determineCandidateConstructors方法中推断,由AutowiredAnnotationBeanPostProcessor实现

```java
public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
      throws BeanCreationException {

   /********************************处理@Lookup注解***********************************/
   // Let's check for lookup methods here...
   if (!this.lookupMethodsChecked.contains(beanName)) {
      if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
         try {
            Class<?> targetClass = beanClass;
            do {
               ReflectionUtils.doWithLocalMethods(targetClass, method -> {
                  Lookup lookup = method.getAnnotation(Lookup.class);
                  if (lookup != null) {
                     Assert.state(this.beanFactory != null, "No BeanFactory available");
                     LookupOverride override = new LookupOverride(method, lookup.value());
                     try {
                        RootBeanDefinition mbd = (RootBeanDefinition)
                              this.beanFactory.getMergedBeanDefinition(beanName);
                        mbd.getMethodOverrides().addOverride(override);
                     }
                     catch (NoSuchBeanDefinitionException ex) {
                        throw new BeanCreationException(beanName,
                              "Cannot apply @Lookup to beans without corresponding bean definition");
                     }
                  }
               });
               targetClass = targetClass.getSuperclass();
            }
            while (targetClass != null && targetClass != Object.class);

         }
         catch (IllegalStateException ex) {
            throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
         }
      }
      this.lookupMethodsChecked.add(beanName);
   }

   /********************************处理@Autowired构造方法***********************************/
   // Quick check on the concurrent map first, with minimal locking.
   //获取缓存中的候选构造方法的列表
   Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
   if (candidateConstructors == null) {
      // Fully synchronized resolution now...
      synchronized (this.candidateConstructorsCache) {
         candidateConstructors = this.candidateConstructorsCache.get(beanClass);
         if (candidateConstructors == null) {
            Constructor<?>[] rawCandidates;
            try {
               //获取所有构造方法
               rawCandidates = beanClass.getDeclaredConstructors();
            }
            catch (Throwable ex) {
               throw new BeanCreationException(beanName,
                     "Resolution of declared constructors on bean Class [" + beanClass.getName() +
                     "] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
            }
            //存储合格的构造方法
            List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
            /**这个是存储加了@Autowired而且其中的require=true的构造方法 */
            Constructor<?> requiredConstructor = null;
            //这个用来存储默认构造方法
            Constructor<?> defaultConstructor = null;
            //获取主构造方法（Kotlin classes相关）
            /**
             * 让kotlin来推断primaryConstructor
             * 如果beanClass这个类不是kotlin类，那么返回null，所有正常情况不用管这个primaryConstructor
             */
            Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
            //记录不是合成方法的构造函数的数量
            int nonSyntheticConstructors = 0;
            //遍历构造方法
            for (Constructor<?> candidate : rawCandidates) {
               //计数加一
               if (!candidate.isSynthetic()) {
                  nonSyntheticConstructors++;
               }
               else if (primaryConstructor != null) {
                  continue;
               }
               //获取当前构造方法的@Autowired或者@Value注解信息
               //拿到require这个属性
               MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
               if (ann == null) {
                  //获取父类类（解决cglib生成的代理类这种情况）
                  //此方法的目的是拿到父类类：比如若是被cglib代理过的，那就拿到父类（因为cglib是通过子类的形式加强的）
                  Class<?> userClass = ClassUtils.getUserClass(beanClass);
                  // 说明确实是被CGLIB代理过的，那就再解析一次  看看父类是否有@Autowaired这种构造器
                  if (userClass != beanClass) {
                     //获取父类类构造方法上的注解信息
                     try {
                        Constructor<?> superCtor =
                              userClass.getDeclaredConstructor(candidate.getParameterTypes());
                        ann = findAutowiredAnnotation(superCtor);
                     }
                     catch (NoSuchMethodException ex) {
                        // Simply proceed, no equivalent superclass constructor found...
                     }
                  }
               }
               // 这里是是存在注解标注的这种构造器的
               if (ann != null) {
                  // 这个判断很有必要，表示要求的构造器最多只能有一个
                  //@Autowired标注的构造器数量最多只能有一个（当然，required=true的只能有一个，=false的可以有多个）
                  if (requiredConstructor != null) {
                     throw new BeanCreationException(beanName,
                           "Invalid autowire-marked constructor: " + candidate +
                           ". Found constructor with 'required' Autowired annotation already: " +
                           requiredConstructor);
                  }
                  //获取@Autowired注解中required属性的值
                  boolean required = determineRequiredStatus(ann);
                  //required属性为true的时候，只能存在一个有@Autowired注解的构造方法
                  if (required) {
                     if (!candidates.isEmpty()) {
                        throw new BeanCreationException(beanName,
                              "Invalid autowire-marked constructors: " + candidates +
                              ". Found constructor with 'required' Autowired annotation: " +
                              candidate);
                     }
                     //当前构造方法是required=true的构造方法
                     requiredConstructor = candidate;
                  }
                  //将有@Autowired注解的构造方法保存到candidates集合中
                  candidates.add(candidate);
               }
               //没有@Autowired注解，并且当前方法参数为0说明是默认构造方法
               // 若该构造器没有被标注@Autowired注解，但是它是无参构造器，那就当然候选的构造器
               else if (candidate.getParameterCount() == 0) {
                  // 这里注意：虽然把默认的构造函数记录下来了，但是并没有加进candidates里
                  defaultConstructor = candidate;
               }
            }
            if (!candidates.isEmpty()) {
               //有加了@Autowired的构造方法
               // Add default constructor to list of optional constructors, as fallback.
               // 这个是candidates里面有值了，并且还没有requiredConstructor
               // （相当于标注了注解@Autowired，但是required=false）的情况下，会把默认的构造函数加进candidates
               if (requiredConstructor == null) {
                  if (defaultConstructor != null) {
                     //将默认构造方法添加到候选构造方法中
                     candidates.add(defaultConstructor);
                  }
                  //不存在默认构造方法，且只有一个构造方法加了@Autowired注解，打印一下日志信息
                  else if (candidates.size() == 1 && logger.isInfoEnabled()) {
                     logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
                           "': single autowire-marked constructor flagged as optional - " +
                           "this constructor is effectively required since there is no " +
                           "default constructor to fall back to: " + candidates.get(0));
                  }
               }
               //集合转数组
               candidateConstructors = candidates.toArray(new Constructor<?>[0]);
            }
            //没有加@Autowired的构造方法，且只有一个有参的构造方法，那么就使用当前构造方法
            else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
               candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
            }
            //没有加@Autowired的构造方法，主构造方法（Kotlin classes相关）和defaultConstructor不为空，那么就使用primaryConstructor和默认构造方法
            //这个条件一般都不成立，只有当前类为kotlin时才考虑
            else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
                  defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
               candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
            }
            //没有加@Autowired的构造方法，主构造方法（Kotlin classes相关）不为空，那么就使用primaryConstructor
            else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
               candidateConstructors = new Constructor<?>[] {primaryConstructor};
            }
            //空的构造方法数组
            else {
               candidateConstructors = new Constructor<?>[0];
            }
            //缓存当前解析过的构造方法
            this.candidateConstructorsCache.put(beanClass, candidateConstructors);
         }
      }
   }
   // 若有多个构造函数，但是没有一个标记了@Autowired,此处不会报错，但是返回null，交给后面的策略处理
   return (candidateConstructors.length > 0 ? candidateConstructors : null);
}
```

1.推断有几个构造方法
determineCandidateConstructors原理：
如果只有一个默认构造方法，不推断，为0，因为后面返回null直接调用无参构造方法就行.(没必要推断)
如果提供了两个以上（包含默认构造方法），且没有加@Autowire，那么spring就会迷茫，不知道用哪个，就直接返回null。(后面又是调用无参构造)
如果只有一个非默认构造方法，那么spring就会推断出这一个来，ctors就有一个值.
如果有多个且没有默认构造方法，那么会报错.
对于@Autowire 推断构造方法时，如果require=true，那么只能有一个@Autowire，如果require=false，那么可以有多个@Autowire。
如果有多个@Autowire标注的构造并且有默认构造方法，就返回@Autowire的数量+1
如果有多个@Autowire标注的构造并且没有默认构造方法，就返回@Autowire的数量，并且打印日志，不报异常。



第二次推断构造方法是在autowireConstructor这个方法中

```java
public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
      @Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

   //创建并初始化BeanWrapper
   BeanWrapperImpl bw = new BeanWrapperImpl();
   this.beanFactory.initBeanWrapper(bw);
   //spring决定采用哪个构造方法来实例化bean
   //代码执行到这里spring已经决定要采用一个特殊构造方法来实例化bean
   //但是到底用哪个？可能这个类提供了很多构造方法
   //采用哪个，spring有自己的一套规则
   //当他找到一个之后他就会把这个构造方法赋值给constructorToUse
   Constructor<?> constructorToUse = null;
   //构造方法的值，注意不是参数
   //在调用反射实例化对象的时候，需要具体的值
   //这个变量就是用来记录这些值
   //但是这里要注意argsHolderToUse是一个数据结构
   //argsToUse[]才是真正的值
   ArgumentsHolder argsHolderToUse = null;
   Object[] argsToUse = null;

   //确定参数值列表

   /**
    * getBean方法可以自定构造方法参数值
    * <T> T getBean(Class<T> requiredType, Object... args) throws BeansException;
    */
   //如果构造参数不为空就直接使用这些参数即可
   if (explicitArgs != null) {
      argsToUse = explicitArgs;
   }
   else {
      Object[] argsToResolve = null;
      synchronized (mbd.constructorArgumentLock) {
         //获取已解析的构造方法
         //一般不会有，因为构造方法一般会提供一个
         //除非有多个，那么才会存在已经解析完成的构造方法
         //获取缓存的构造方法和参数（主要为方便创建Protype类型的对象）
         constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
         if (constructorToUse != null && mbd.constructorArgumentsResolved) {
            // Found a cached constructor...
            argsToUse = mbd.resolvedConstructorArguments;
            if (argsToUse == null) {
               //获取部分准备好的构造方法参数
               argsToResolve = mbd.preparedConstructorArguments;
            }
         }
      }
      //解析部分准备好的构造方法参数
      if (argsToResolve != null) {
         argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
      }
   }

   if (constructorToUse == null || argsToUse == null) {
      // Take specified constructors, if any.
      /**
       * 如果指定了构造方法，就从指定的构造方法找出最合适的构造方法
       * 在bean实例化之前，会调用这个AutowiredAnnotationBeanPostProcessor增强器的
       * determineCandidateConstructors方法，该方法返回有@Autowired注解的构造方法
       * chosenCtors就是determineCandidateConstructors方法的返回值
       */
      Constructor<?>[] candidates = chosenCtors;
      //为空的话，就先获取所有构造方法，然后从中找出最合适的构造方法实例化
      if (candidates == null) {
         Class<?> beanClass = mbd.getBeanClass();
         try {
            /**
             * mbd.isNonPublicAccessAllowed() 判断是否允许使用非public的构造方法实例化对象
             * beanClass.getDeclaredConstructors() 获取所有的构造方法
             * beanClass.getConstructors() 只获取public的构造方法
             */
            candidates = (mbd.isNonPublicAccessAllowed() ?
                  beanClass.getDeclaredConstructors() : beanClass.getConstructors());
         }
         catch (Throwable ex) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                  "Resolution of declared constructors on bean Class [" + beanClass.getName() +
                  "] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
         }
      }

      /*******************************单构造方法************************************/
      if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
         Constructor<?> uniqueCandidate = candidates[0];
         //构造方法参数个数为0，实际就是默认构造方法
         if (uniqueCandidate.getParameterCount() == 0) {
            //缓存构造方法和参数
            synchronized (mbd.constructorArgumentLock) {
               mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
               mbd.constructorArgumentsResolved = true;
               mbd.resolvedConstructorArguments = EMPTY_ARGS;
            }
            //使用该构造方法实例化
            bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
            return bw;
         }
      }

      /*******************************多构造方法************************************/
      // Need to resolve the constructor.
      //要么指定了构造方法，要么开启了自动注入构造
      boolean autowiring = (chosenCtors != null ||
            mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
      //存放解析出来的参数
      ConstructorArgumentValues resolvedValues = null;

      //定义了最小参数个数
      //如果你给构造方法的参数列表给定了具体的值
      //那么这些值的个数就是构造方法参数的个数
      int minNrOfArgs;
      //当你手动调用doGetbean方法并且args参数有值时，explicitArgs才不为空
      if (explicitArgs != null) {
         minNrOfArgs = explicitArgs.length;
      }
      else {
         /**
          * cargs获取构造方法的值，注意是值不是类型和列表
          */
         //获取beanDefinition中缓存的构造方法参数值ConstructorArgumentValues
         ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
         //resolvedValues实例化一个对象，用来存放构造方法的参数值
         resolvedValues = new ConstructorArgumentValues();
         //类型和个数是spring用来确定使用哪个构造方法的重要信息
         minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
      }

      //根据构造方法的访问权限级别和参数数量进行排序
      //怎么排序呢？
      //先对访问权限排，再对参数数量
      /**
       * 对构造方法进行排序,会按照如下顺序
       * public并且方法参数越多越靠前
       * 非public并且方法参数越多越靠前
       */
      AutowireUtils.sortConstructors(candidates);
      //定义了一个差异变量，默认值为最大值int的
      //spring会根据每个构造方法的差异量，选出一个最小的
      int minTypeDiffWeight = Integer.MAX_VALUE;
      //存储模糊不清的（差异值相同）的构造方法
      Set<Constructor<?>> ambiguousConstructors = null;
      LinkedList<UnsatisfiedDependencyException> causes = null;

      //循环所有构造方法
      for (Constructor<?> candidate : candidates) {
         //获取当前构造方法的参数数量
         int parameterCount = candidate.getParameterCount();

         //满足下面三个条件，说明该构造方法可用，直接使用当前构造方法和参数
         /**
          * 这个判断别看只有一行代码理解起来很费劲
          * 首先constructorToUse != null这个很好理解，
          * *前面已经说过首先constructorToUise主要是用来装已经解析过了并且在使用的构造方法*
          * 只有在他等于空的情况下，才有继续的意义，因为下面如果解析到了一个符合的构造方法
          * *就会赋值给这个变量（下面注释有写)。故而如果这个变量不等于null就不需要再进行解析了，
          * 说明spring已经找到一个合适的构造方法，直接使用便可以
          * * argsToUse.length > paramTypes.length这个代码就相当复杂了
          * *首先假设 argsToUse = [1，"luban", obj]
          * * argsToUse>paramTypes这个很精髓-------因为排序，因为有多个，第二次循环
          * *多到少，如果第一个都比argsToUse小，那么后面的就不需要去判断了
          */
         if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
            // Already found greedy constructor that can be satisfied ->
            // do not look any further, there are only less greedy constructors left.
            break;
         }
         //匹配的参数数量不够，继续下一个构造方法
         if (parameterCount < minNrOfArgs) {
            continue;
         }

         ArgumentsHolder argsHolder;
         //获取构造方法的参数类型
         Class<?>[] paramTypes = candidate.getParameterTypes();
         if (resolvedValues != null) {
            try {
               /**
                * 解析@ConstructorProperties注解
                * 这个注解实际上就是显示指定构造方法的参数名
                * 通过此注解可以直接获取参数名
                */
               //@ConstructorProperties(value={"xxx","111"})
               String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
               if (paramNames == null) {
                  //没有手动标注参数名，就使用ParameterNameDiscoverer解析构造方法的参数名
                  ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
                  if (pnd != null) {
                     /**
                      * 获取构造方法的参数名列表
                      * 实际上流程很简单，都是反射的知识
                      * Parameter[] parameters=Constructor.getParameters() 获取所有的参数
                      * parameter.getName() 遍历获取每个参数的参数名字
                      */
                     paramNames = pnd.getParameterNames(candidate);
                  }
               }
               //获取构造方法参数值列表
               /**
                * 创建ArgumentsHolder
                * getUserDeclaredConstructor(candidate)
                * 从名字上理解，就是获取用户声明的构造方法，这主要是避免这么一种情况，
                * 当前这个构造方法所属的类是由cglib生成的子类，那么此时，就不能使用子类的
                * 必须得获取原始类的构造方法
                */
               /**
                *这个方法比较复杂
                * 因为spring只能提供字符串的参数值
                * 故而需要进行转换
                * argsHolder所包含的值就是转换之后的
                */
               argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
                     getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
            }
            catch (UnsatisfiedDependencyException ex) {
               if (logger.isTraceEnabled()) {
                  logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
               }
               // Swallow and try next constructor.
               if (causes == null) {
                  causes = new LinkedList<>();
               }
               causes.add(ex);
               continue;
            }
         }
         else {
            // Explicit arguments given -> arguments length must match exactly.
            if (parameterCount != explicitArgs.length) {
               continue;
            }
            argsHolder = new ArgumentsHolder(explicitArgs);
         }

         /**
          * mbd.isLenientConstructorResolution()获取构造方法的匹配模式（宽松、严格）
          * 使用算法计算出当前构造方法的权重值（值越小越匹配）
          */
         int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
               argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
         // Choose this constructor if it represents the closest match.
         //使用权重值小的构造方法
         if (typeDiffWeight < minTypeDiffWeight) {
            //将当前构造方法设置为使用的构造方法
            constructorToUse = candidate;
            argsHolderToUse = argsHolder;
            //将当前参数设置为构造方法使用的参数
            argsToUse = argsHolder.arguments;
            //覆盖上一个构造方法的权重值
            minTypeDiffWeight = typeDiffWeight;
            ambiguousConstructors = null;
         }
         else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
            if (ambiguousConstructors == null) {
               ambiguousConstructors = new LinkedHashSet<>();
               ambiguousConstructors.add(constructorToUse);
            }
            ambiguousConstructors.add(candidate);
         }
      }

      //找不到合适的构造方法，抛异常
      if (constructorToUse == null) {
         if (causes != null) {
            UnsatisfiedDependencyException ex = causes.removeLast();
            for (Exception cause : causes) {
               this.beanFactory.onSuppressedException(cause);
            }
            throw ex;
         }
         throw new BeanCreationException(mbd.getResourceDescription(), beanName,
               "Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
               "(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
      }
      else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
         throw new BeanCreationException(mbd.getResourceDescription(), beanName,
               "Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
               "(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
               ambiguousConstructors);
      }

      //缓存当前使用的构造方法和参数，以备下次实例化对象使用
      if (explicitArgs == null && argsHolderToUse != null) {
         argsHolderToUse.storeCache(mbd, constructorToUse);
      }
   }

   Assert.state(argsToUse != null, "Unresolved constructor arguments");
   //instantiate  实例化对象
   bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
   return bw;
}
```

2.推断选择哪个构造方法

autowireConstructor原理:
首先会判断第一次推断构造方法有没有返回值，如果为null，则会根据是否允许使用非public的构造方法实例化对象获取所有构造方法或public构造
方法，如果不为null，以第一次推断得到的来进行选择。
如果只有一个构造方法并且为默认构造方法，就选择这个默认无参的构造进行实例化.
有多个构造方法的情况：
对这些构造方法先进行排序，排序规则为先按访问权限进行排，再对参数数量进行排，public并且方法参数越多越靠前
该方法中有个非常重要的变量minTypeDiffWeight，这是一个差异变量，spring会根据每个构造方法的差异量，选出一个最小的
如果有多个相同差异变量的构造方法，如果spring使用宽松的模式解析构造函数（默认使用），就选取，第一个被解析的构造方法，
如果不使用，就报错。

# 3.循环依赖

首先说一下循环依赖的大致流程

A->推断构造方法->实例化->进行属性注入(假设这时要注入B)->getB->new B->进行B的属性注入（这时要注入A）

->getA(因为spring默认开启了循环依赖，所以此时能获取到A，如果A还需要被代理的话，此时会返回A的代理对象)->A注入到B->B走完bean的生命周期，进入容器->然后A注入B->A走完bean的生命周期->到这里的话循环依赖完成。

最主要的是进行B的属性注入时为什么能够获取到A?

首先，A在属性注入前，会提前暴露一个工厂对象

```java
// Eagerly cache singletons to be able to resolve circular references
// even when triggered by lifecycle interfaces like BeanFactoryAware.
//spring为了解决循环依赖而做的工作
/**
 * 判断是否需要提前将该bean实例暴露
 * isSingletonCurrentlyInCreation(beanName) 判断当前beanName对应的bean是否正在创建
 * 只会暴露当前正在创建的单例bean
 */
//如果当前bean是单例，且支持循环依赖，且当前bean正在创建，通过往singletonFactories添加一个objectFactory，
// 这样后期如果有其他bean依赖该bean 可以从singletonFactories获取到bean
//getEarlyBeanReference可以对返回的bean进行修改，这边目前除了可能会返回动态代理对象 其他的都是直接返回bean
boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
      isSingletonCurrentlyInCreation(beanName));
if (earlySingletonExposure) {
   if (logger.isTraceEnabled()) {
      logger.trace("Eagerly caching bean '" + beanName +
            "' to allow for resolving potential circular references");
   }
   /**
    * 创建一个ObjectFactory，它的getObject方法返回的是经过getEarlyBeanReference方法
    * 增强的bean（此时的bean才刚实例化完成，还没有经过属性填充和初始化）
    * 并把这个ObjectFactory工厂放入第二级缓存中
    */
   addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
}
```

```java
protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
   Assert.notNull(singletonFactory, "Singleton factory must not be null");
   //singletonObjects 一级缓存
   synchronized (this.singletonObjects) {
      /**
       * 如果单例池当中不存在才会add
       * 因为这里主要是为了循环依赖服务的代码
       * 如果bean存在单例池的话其实已经是一个完整的bean了
       * 一个完整的bean自然已经完成了属性注入，循环依赖已经依赖上了
       * 所以如果这个对象已经是一个完整bean，就不需要关系，不需要进if
       */
      if (!this.singletonObjects.containsKey(beanName)) {
         //把工厂对象put到二级缓存---singletonFactories
         this.singletonFactories.put(beanName, singletonFactory);
         //从三级缓存中remove掉当前bean
         //为什么需要remove？抛开细节，这三个map当中其实存的就是一个对象
         //spring的做法是三个不能同时都存，假如1存了，则2和3就要remove
         //现在既然put到了2级缓存，1已经判断没有了，3就直接remove
         this.earlySingletonObjects.remove(beanName);
         this.registeredSingletons.add(beanName);
      }
   }
}
```

当A进行属性注入，要注入B，此时容器中没有，会getB，然后创建B，对B进行属性注入，然后又要getA，在doGetBean()方法中有一句非常重要的代码

```
Object sharedInstance = getSingleton(beanName);
```

他会先从容器获取A，此时因为A提前暴露了一个工厂对象，所以能通过这个工厂对象的getObject方法获取到一个半成品A，或者一个被代理的A

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
   // Quick check for existing instance without full singleton lock
   //从单例池（一级缓存）中直接拿
   //这也是为什么getBean("xx")能获取一个初始化好bean的根本代码
   Object singletonObject = this.singletonObjects.get(beanName);
   //如果这个时候是x注入y，创建y，y注入x，获取x的时候那么x不在容器
   //第一个singletonObject==null成立
   //第二个条件判断是否存在正在创建bean的集合中，前面分析是成立的
   //进入if分支
   if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
      //先从三级缓存拿x？为什么先从三级缓存拿？
      //避免x已经产生过一次，为什么？ 如果x还依赖z，z也依赖x,那么这次就能从这里拿到x不用再走下面的流程
      singletonObject = this.earlySingletonObjects.get(beanName);
      //这里应该是拿不到的，因为这三个map中只有二级缓存中存了一个工厂对象
      //所以三级缓存拿到的singletonObject==null 第一个条件成立
      //第二个条件allowEarlyReference=true，这个前文设置过
      //就是spring循环依赖的开关，默认为true 进入if分支
      if (singletonObject == null && allowEarlyReference) {
         synchronized (this.singletonObjects) {
            // Consistent creation of early reference within full singleton lock
            singletonObject = this.singletonObjects.get(beanName);
            if (singletonObject == null) {
               singletonObject = this.earlySingletonObjects.get(beanName);
               if (singletonObject == null) {
                  //从二级缓存中获取一个singletonFactory
                  //由于这里的beanName=x，故而获取出来的工厂对象，能产生一个x半成品bean
                  // 某些方法提前初始化的时候会调用addSingletonFactory，把ObjectFactory缓存在singletonFactories中
                  ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
                  //由于获取到了，进入if分支
                  if (singletonFactory != null) {
                     //调用工厂对象的getObject（）方法，产生一个x的半成品bean
                     //怎么产生的？
                     singletonObject = singletonFactory.getObject();
                     //拿到了半成品的xbean后，把他放到三级缓存，为什么？
                     // 为了下次再次获取时能直接拿到，而不需要再次生成
                     this.earlySingletonObjects.put(beanName, singletonObject);
                     //然后从二级缓存中清除掉x的工厂对象，为什么？
                     // 为了gc,提高性能
                     this.singletonFactories.remove(beanName);
                  }
               }
            }
         }
      }
   }
   return singletonObject;
}
```

工厂对象的getObject实际就是调用getEarlyBeanReference方法

```java
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
   Object exposedObject = bean;
   if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
      for (BeanPostProcessor bp : getBeanPostProcessors()) {
         if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
            SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
            //可以调用getEarlyBeanReference对实例化后的bean增强
            exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
         }
      }
   }
   return exposedObject;
}
```

spring中有三个map

```java
//一级缓存
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
//二级缓存
private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);
//三级缓存
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);
```

