前言:spring中最重要的就是refresh方法，所以学习spring源码主要围绕这个方法进行学习。

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

# 4.@Autowired注入原理

首先在doCreateBean方法中有一段代码

```java
applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
```

这是spring第三次调用后置处理器，关于后置处理器，后续再说

在这段代码中缓存所有的@Autowired的注解的Member(filed method)，同时@PostConstruct,@Value,

@Resource,@PreDestory这些也缓存了(这是spring策略模式的体现)，这里只讨论@Autowired

```java
public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
   //查找类中的@Autowired和@Value注解，获取注入元数据
   InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
   //将需要自动注入的属性记录到BeanDefinition中
   metadata.checkConfigMembers(beanDefinition);
}
```

```java
private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
   // Fall back to class name as cache key, for backwards compatibility with custom callers.
   String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
   // Quick check on the concurrent map first, with minimal locking.
   //获取缓存中对应的注入元数据
   InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
   //注入元数据是否需要刷新（判断clazz是否相同）
   if (InjectionMetadata.needsRefresh(metadata, clazz)) {
      synchronized (this.injectionMetadataCache) {
         metadata = this.injectionMetadataCache.get(cacheKey);
         if (InjectionMetadata.needsRefresh(metadata, clazz)) {
            if (metadata != null) {
               metadata.clear(pvs);
            }
            //解析clazz，获取类中的注入元数据
            metadata = buildAutowiringMetadata(clazz);
            //将该类的注入元数据缓存缓存
            this.injectionMetadataCache.put(cacheKey, metadata);
         }
      }
   }
   return metadata;
}
```

```java
private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
   /**
    * AutowiredAnnotationBeanPostProcessor默认构造方法会添加两个注解类到集合中
    * this.autowiredAnnotationTypes.add(Autowired.class);
    * this.autowiredAnnotationTypes.add(Value.class);
    * 这个方法的作用就是判断类中有没有携带这两个注解
    * 没有直接返回空
    */
   if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
      //表示一个空的注入元数据
      return InjectionMetadata.EMPTY;
   }

   List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
   Class<?> targetClass = clazz;

   do {
      final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();

      /************************************属性上的注解***************************************/

      //遍历targetClass类的属性,回调接口方法
      //找所有的符合要求的属性
      ReflectionUtils.doWithLocalFields(targetClass, field -> {
         //获取属性上的注解信息
         MergedAnnotation<?> ann = findAutowiredAnnotation(field);
         if (ann != null) {
            //静态属性
            if (Modifier.isStatic(field.getModifiers())) {
               if (logger.isInfoEnabled()) {
                  logger.info("Autowired annotation is not supported on static fields: " + field);
               }
               return;
            }
            //判断注解中required属性是否为true，没有该属性（@Value）直接false
            boolean required = determineRequiredStatus(ann);
            //将当前解析的注解信息添加到缓存中
            currElements.add(new AutowiredFieldElement(field, required));
         }
      });

      /************************************方法上的注解***************************************/
      ReflectionUtils.doWithLocalMethods(targetClass, method -> {
         //获取方法对应的桥接方法（jvm相关知识点）
         Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
         if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
            return;
         }
         //获取方法上的注解，和获取属性上的注解方法一模一样
         MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
         //getMostSpecificMethod 将指定类接口方法对象转化为指定类的方法对象
         if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
            if (Modifier.isStatic(method.getModifiers())) {
               if (logger.isInfoEnabled()) {
                  logger.info("Autowired annotation is not supported on static methods: " + method);
               }
               return;
            }
            //标注在方法上，方法必须有参数
            if (method.getParameterCount() == 0) {
               if (logger.isInfoEnabled()) {
                  logger.info("Autowired annotation should only be used on methods with parameters: " +
                        method);
               }
            }
            //获取注解required属性的值
            boolean required = determineRequiredStatus(ann);
            //找到当前方法的对应属性描述（解决@Autowired标注在get，set方法上）
            PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
            //缓存
            currElements.add(new AutowiredMethodElement(method, required, pd));
         }
      });

      elements.addAll(0, currElements);
      //获取父类
      targetClass = targetClass.getSuperclass();
   }
   //它还要对父类里面的属性和方法在判断一次，一直到最顶层Object类
   while (targetClass != null && targetClass != Object.class);

   //根据注解信息构建注入元数据
   return InjectionMetadata.forElements(elements, clazz);
}
```

```java
private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
   //获取该属性的注解数据
   MergedAnnotations annotations = MergedAnnotations.from(ao);
   //遍历，判断该属性上的注解到底是哪一个（@Autowired，@Value）
    //this.autowiredAnnotationTypes在调用AutowiredAnnotationBeanPostProcessor构造方法的时候就把Autowired.class和Value.class存入了.
   for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
      MergedAnnotation<?> annotation = annotations.get(type);
      if (annotation.isPresent()) {
         return annotation;
      }
   }
   //无注解，返回null
   return null;
}
```



```java
public void checkConfigMembers(RootBeanDefinition beanDefinition) {
   Set<InjectedElement> checkedElements = new LinkedHashSet<>(this.injectedElements.size());
   //遍历
   for (InjectedElement element : this.injectedElements) {
      //获取被注入元素的属性对象
      Member member = element.getMember();
      //将当前需要自动注入的属性或方法保存到BeanDefinition中
      if (!beanDefinition.isExternallyManagedConfigMember(member)) {
         beanDefinition.registerExternallyManagedConfigMember(member);
         checkedElements.add(element);
      }
   }
    //最后这些数据都存到这个集合中，后面注入的时候也是从这个集合中拿
   this.checkedElements = checkedElements;
}
```

然后在populateBean方法中

```java
for (BeanPostProcessor bp : getBeanPostProcessors()) {
   if (bp instanceof InstantiationAwareBeanPostProcessor) {
      InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
      //此方法完成@Autowired和@Value注解属性自动注入
      PropertyValues pvsToUse = ibp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
      if (pvsToUse == null) {
         if (filteredPds == null) {
            filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
         }
         pvsToUse = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
         if (pvsToUse == null) {
            return;
         }
      }
      pvs = pvsToUse;
   }
}
```

在postProcessProperties这个方法完成的@AutoWired的注入



```java
public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
   //查找类中的@Autowired和@Value注解,获取注入元数据,这里再次查找一遍
   InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
   try {
      //注入属性
      metadata.inject(bean, beanName, pvs);
   }
   catch (BeanCreationException ex) {
      throw ex;
   }
   catch (Throwable ex) {
      throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
   }
   return pvs;
}
```

```java
public void inject(Object target, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
   //已经记录的需要自动注入的属性
   Collection<InjectedElement> checkedElements = this.checkedElements;
   Collection<InjectedElement> elementsToIterate =
         (checkedElements != null ? checkedElements : this.injectedElements);
   if (!elementsToIterate.isEmpty()) {
      //遍历获取每个需要被注入的元素（属性或方法）
      for (InjectedElement element : elementsToIterate) {
         //无论是属性或方法都封装为ResourceElement
         element.inject(target, beanName, pvs);
      }
   }
}
```

InjectedElement有两种实现类AutowiredFieldElement和AutowiredMethodElement,分别对应着属性和方法(在其上面加了@Autowired注解),这里主要来看AutowiredFieldElement的实现，AutowiredMethodElement的实现类似.



```java
// 这段代码虽然长，其实核心逻辑还并不在这里，而是在beanFactory Bean工厂的resolveDependency处理依赖实现里
@Override
protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
   Field field = (Field) this.member;
   Object value;
   //已经被缓存过了，直接获取缓存中的值
   //是否有缓存，区别在于是否需要对属性进行解析--获取需要注入的值
   if (this.cached) {
      try {
         value = resolvedCachedArgument(beanName, this.cachedFieldValue);
      }
      catch (NoSuchBeanDefinitionException ex) {
         // Unexpected removal of target bean for cached argument -> re-resolve
         value = resolveFieldValue(field, bean, beanName);
      }
   }
   //未缓存，从BeanFactory中获取
   else {
      value = resolveFieldValue(field, bean, beanName);
   }
   //反射，强行设置属性值（无论有没有set方法）
   if (value != null) {
      ReflectionUtils.makeAccessible(field);
      field.set(bean, value);
   }
}
```

可以看到最后的注入非常简单，就是用反射，注入属性值,其中，最复杂的是从beanFactory中获取要注入的值



```java
//根据工厂中定义的bean解析指定的依赖项
public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
      @Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

   /**
    * getParameterNameDiscoverer() 获取工厂中的ParameterNameDiscoverer用来解析方法参数名
    * initParameterNameDiscovery() 实际上就是将ParameterNameDiscoverer设置到依赖描述中
    * 在后面依赖描述会调用ParameterNameDiscoverer来解析方法参数名字
    */
   descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());

   /******************************特殊类型，特殊处理**************************************/
   if (Optional.class == descriptor.getDependencyType()) {
      return createOptionalDependency(descriptor, requestingBeanName);
   }
   else if (ObjectFactory.class == descriptor.getDependencyType() ||
         ObjectProvider.class == descriptor.getDependencyType()) {
      return new DependencyObjectProvider(descriptor, requestingBeanName);
   }
   else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
      return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
   }

   /***************************************普通类型**************************************/
   else {
      //getAutowireCandidateResolver()得到ContextAnnotationAutowireCandidateResolver 根据依赖注解信息，找到对应的Bean值信息
      //getLazyResolutionProxyIfNecessary方法，它也是唯一实现。
      //如果字段上带有@Lazy注解，表示进行懒加载 Spring不会立即创建注入属性的实例，而是生成代理对象，来代替实例
      Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
            descriptor, requestingBeanName);
      if (result == null) {
         // 如果在@Autowired上面还有个注解@Lazy，那就是懒加载的，是另外一种处理方式（是一门学问）
         // 这里如果不是懒加载的（绝大部分情况都走这里） 就进入核心方法doResolveDependency 下面有分解
         result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
      }
      return result;
   }
}
```



```java

public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
      @Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

   InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
   try {
      // spring第一次创建依赖的时候，会存放在shortcut，后面就不用再解析了
      Object shortcut = descriptor.resolveShortcut(this);
      if (shortcut != null) {
         return shortcut;
      }

      // 获取需要注入属性的类型
      Class<?> type = descriptor.getDependencyType();

      // 看看ContextAnnotationAutowireCandidateResolver的getSuggestedValue方法,
      // 具体实现在父类 QualifierAnnotationAutowireCandidateResolver中
      //处理@Value注解-------------------------------------
      //获取@Value中的value属性
      Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
      // 若存在value值，那就去解析它。使用到了AbstractBeanFactory#resolveEmbeddedValue
      // 也就是使用StringValueResolver处理器去处理一些表达式~~
      if (value != null) {
         // 解析value
         if (value instanceof String) {
            String strVal = resolveEmbeddedValue((String) value);
            BeanDefinition bd = (beanName != null && containsBean(beanName) ?
                  getMergedBeanDefinition(beanName) : null);
            value = evaluateBeanDefinitionString(strVal, bd);
         }
         //如果需要会进行类型转换后返回结果
         TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
         try {
            return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
         }
         catch (UnsupportedOperationException ex) {
            // A custom TypeConverter which does not support TypeDescriptor resolution...
            return (descriptor.getField() != null ?
                  converter.convertIfNecessary(value, type, descriptor.getField()) :
                  converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
         }
      }

      //对数组、Collection、Map等类型进行处理，也是支持自动注入的。
      //因为是数组或容器，Sprng可以直接把符合类型的bean都注入到数组或容器中，处理逻辑是：
      //1.确定容器或数组的组件类型 if else 分别对待，分别处理
      //2.调用findAutowireCandidates（核心方法）方法，获取与组件类型匹配的Map(beanName -> bean实例)
      //3.将符合beanNames添加到autowiredBeanNames中
      Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
      if (multipleBeans != null) {
         return multipleBeans;
      }

      // 获取所有【类型】匹配的Beans，形成一个Map（此处用Map装，是因为可能不止一个符合条件）
      // 该方法就特别重要了，对泛型类型的匹配、对@Qualifierd的解析都在这里面，下面详情分解
      Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
      // 若没有符合条件的Bean。。。
      if (matchingBeans.isEmpty()) {
         // 为空，且required为true，抛异常
         if (isRequired(descriptor)) {
            raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
         }
         return null;
      }

      String autowiredBeanName;
      Object instanceCandidate;

      // 有多个的情况，说明有多个和该属性相同类型的bean,筛选一个
      if (matchingBeans.size() > 1) {
         // 该方法作用：推断出@Autowired标注的属性的名字
         autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
         if (autowiredBeanName == null) {
            // 如果此Bean是要求的，或者 不是Array、Collection、Map等类型，那就抛出异常NoUniqueBeanDefinitionException
            if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
               // 抛出此异常
               return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
            }
            else {
               //表示如果是required=false，或者就是List Map类型之类的，即使没有找到Bean，也让它不抱错，
               // In case of an optional Collection/Map, silently ignore a non-unique case:
               // possibly it was meant to be an empty collection of multiple regular beans
               // (before 4.3 in particular when we didn't even look for collection beans).
               return null;
            }
         }
         //根据名字从根据类型找出来的map<beanName,class>当中获取
         instanceCandidate = matchingBeans.get(autowiredBeanName);
      }
      else {
         // We have exactly one match.
         // 只有一个，直接返回
         Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
         autowiredBeanName = entry.getKey();
         //instanceCandidate可以是已经实例化的对象，也可以是对应类型的class对象
         instanceCandidate = entry.getValue();
      }

      // 把找到的autowiredBeanName 放进去
      if (autowiredBeanNames != null) {
         autowiredBeanNames.add(autowiredBeanName);
      }

      if (instanceCandidate instanceof Class) {
         //getBean(autowiredBeanName)获取实例
         instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
      }
      //赋值给result，返回result
      Object result = instanceCandidate;
      if (result instanceof NullBean) {
         if (isRequired(descriptor)) {
            raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
         }
         result = null;
      }
      //类型不匹配，抛异常
      if (!ClassUtils.isAssignableValue(type, result)) {
         throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
      }
      return result;
   }
   finally {
      ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
   }
}
```

总结一下获取值的流程，首先获取这个属性的类型，然后根据类型去容器找出所有该类型对应的bean的名字，然后根据名字找出对应的类型 最后put到集合中，candidates.put(candidateName, getType(candidateName));

然后就得到了matchingBeans这个集合，如果这个集合size>1,说明有多种类型（注入的类型有多种子类的情况）

这时就不能根据类型去获取值了，所以就会根据名字去获取

```java
// 该方法作用：推断出@Autowired标注的属性的名字
autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
```

这就是@Autowired先根据类型获取，获取不到，再根据名字获取的原理.

# 5.自动注入原理(以AUTOWIRE_BY_TYPE为例)

首先了解一下java的内省机制：

JavaBean （有get/set属性，和默认构造器等规范的java类）有这样的特征：

- 属性都是私有的；
- 有无参的public构造方法；
- 对私有属性根据需要提供公有的getXxx方法以及setXxx方法；

内省(Inspector)机制就是基于反射的基础， Java语言对Bean类属性、事件的一种缺省处理方法。

只要类中有getXXX方法，或者setXXX方法，或者同时有getXXX及setXXX方法，其中getXXX方 法没有方法参数，有返回值； setXXX方法没有返回值，有一个方法参数；那么内省机制就认为 XXX为一个属性；

举一个典型的例子就是Object类的class属性，在Object类中没有class这个字段，但是我们可以通过getClass方法获取Class对象，这就利用了java的内省机制，Object类中有getClass方法，并且没有参数，有返回值，所以就认为Class为一个属性。

内省的关键代码在Introspector类中的getTargetPropertyInfo方法中，代码如下

```java
if (argCount == 0) {
    //如果方法名称以get开头
    if (name.startsWith(GET_PREFIX)) {
        // Simple getter
        //截取掉get后的字符串作为属性名称
        pd = new PropertyDescriptor(this.beanClass, name.substring(3), method, null);
    } else if (resultType == boolean.class && name.startsWith(IS_PREFIX)) {
        // Boolean getter
        //如果方法名称以is开头，截取掉is后的字符串作为属性名称
        pd = new PropertyDescriptor(this.beanClass, name.substring(2), method, null);
    }
} else if (argCount == 1) {
    //如果方法参数为int类型，且以get开头，pd则为IndexedPropertyDescriptor类型的属性
    if (int.class.equals(argTypes[0]) && name.startsWith(GET_PREFIX)) {
        pd = new IndexedPropertyDescriptor(this.beanClass, name.substring(3), null, null, method, null);
    } else if (void.class.equals(resultType) && name.startsWith(SET_PREFIX)) {
        // Simple setter
        //如果方法没有返回值，且参数不为int，方法名称以set开头
        pd = new PropertyDescriptor(this.beanClass, name.substring(3), null, method);
        if (throwsException(method, PropertyVetoException.class)) {
            pd.setConstrained(true);
        }
    }
} else if (argCount == 2) {
    //如果返回值为void，第一个参数为int，方法名称以set开头，pd为IndexedPropertyDescriptor类型的属性
        if (void.class.equals(resultType) && int.class.equals(argTypes[0]) && name.startsWith(SET_PREFIX)) {
        pd = new IndexedPropertyDescriptor(this.beanClass, name.substring(3), null, null, null, method);
        if (throwsException(method, PropertyVetoException.class)) {
            pd.setConstrained(true);
        }
    }
}
```

下面再来看自动注入(by_type)的代码：

```java
protected void autowireByType(
      String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

   // 类型转换器获取
   //TypeConverter是定义类型转换的接口
   TypeConverter converter = getCustomTypeConverter();
   if (converter == null) {
      //BeanWrapper是TypeConverter的子接口
      converter = bw;
   }

   Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
   //拿到所有属性描述符的名字
   String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
   for (String propertyName : propertyNames) {
      try {
         //得到对应属性名字的属性描述
         PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
         // Don't try autowiring by type for type Object: never makes sense,
         // even if it technically is a unsatisfied, non-simple property.
         // 如果是Object，就不管了,object类型的不能自动注入
         if (Object.class != pd.getPropertyType()) {
            //获取属性set方法对象包装（里面包含方法名）
            MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
            // Do not allow eager init for type matching in case of a prioritized post-processor.
            // 是否立即初始化
            boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
            // 依赖描述
            DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
            // 解析依赖，获取依赖对应的对象
            Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
            if (autowiredArgument != null) {
               //加入到MutablePropertyValues中，等待applyPropertyValues(beanName, mbd, bw, pvs);统一赋值
               pvs.add(propertyName, autowiredArgument);
            }
            for (String autowiredBeanName : autowiredBeanNames) {
               // 注册依赖
               registerDependentBean(autowiredBeanName, beanName);
               if (logger.isTraceEnabled()) {
                  logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
                        propertyName + "' to bean named '" + autowiredBeanName + "'");
               }
            }
            autowiredBeanNames.clear();
         }
      }
      catch (BeansException ex) {
         throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
      }
   }
}
```

关键代码在unsatisfiedNonSimpleProperties方法

```java
protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
   Set<String> result = new TreeSet<>();
   //得到需要设置值的所有属性的键值对
   PropertyValues pvs = mbd.getPropertyValues();
   /**
    * 获取当前bean的属性描述(必然包含当前类的class描述，它也是属性)
    * pd.getWriteMethod() 会获取当前属性set方法对象
    * pd.getPropertyType() 获取当前属性的类型
    * isExcludedFromDependencyCheck 当前属性是否被排除（排除cglib生成的类的内部属性，
    * spring容器的内部属性（实现了spring中的aware接口），这个需要通过BeanPostProcessor接口注入）
    * !pvs.contains(pd.getName()) 已有的属性集合中不包含（非手动注入过的属性）
    * !BeanUtils.isSimpleProperty(pd.getPropertyType()) 非简单属性
    */

   //调用jdk中的方法，拿到bw对应类的beanInfo信息，包含了类中所有对属性的描述方法(get or set)
   //==beanInfo.getPropertyDescriptors
   PropertyDescriptor[] pds = bw.getPropertyDescriptors();
   for (PropertyDescriptor pd : pds) {
      if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
            !BeanUtils.isSimpleProperty(pd.getPropertyType())) {
         result.add(pd.getName());
      }
   }
   return StringUtils.toStringArray(result);
}
```

```java
public PropertyDescriptor[] getPropertyDescriptors() {
   return getCachedIntrospectionResults().getPropertyDescriptors();
}
```



```java
private CachedIntrospectionResults getCachedIntrospectionResults() {
   if (this.cachedIntrospectionResults == null) {
      //内省，获取javabean对象属性信息
      this.cachedIntrospectionResults = CachedIntrospectionResults.forClass(getWrappedClass());
   }
   return this.cachedIntrospectionResults;
}
```

通过spring封装的内省工具类cachedIntrospectionResults的静态方法forClass(beanClass)，得到所有属性描述PropertyDescriptor，然后获取指定属性的属性描述，封装为属性处理器BeanPropertyHandler

CachedIntrospectionResults这个类中封装了内省的结果，并且提供了方法方便获取内省的结果。

继续来看forClass方法

```java
static CachedIntrospectionResults forClass(Class<?> beanClass) throws BeansException {
   //先从缓存中获取
   CachedIntrospectionResults results = strongClassCache.get(beanClass);
   if (results != null) {
      return results;
   }
   results = softClassCache.get(beanClass);
   if (results != null) {
      return results;
   }

   //创建一个beanClass的可缓存的内省结果
   //在这个new的过程中已经把所有的属性描述找出来了
   results = new CachedIntrospectionResults(beanClass);
   ConcurrentMap<Class<?>, CachedIntrospectionResults> classCacheToUse;

   //使用当前类加载器加载的是缓存安全的
   if (ClassUtils.isCacheSafe(beanClass, CachedIntrospectionResults.class.getClassLoader()) ||
         isClassLoaderAccepted(beanClass.getClassLoader())) {
      classCacheToUse = strongClassCache;
   }
   //不安全
   else {
      if (logger.isDebugEnabled()) {
         logger.debug("Not strongly caching class [" + beanClass.getName() + "] because it is not cache-safe");
      }
      classCacheToUse = softClassCache;
   }

   //缓存
   CachedIntrospectionResults existing = classCacheToUse.putIfAbsent(beanClass, results);
   return (existing != null ? existing : results);
}
```

在CachedIntrospectionResults的构造方法中就获取了beanClass类的内省结果

```java
private CachedIntrospectionResults(Class<?> beanClass) throws BeansException {
   try {
      if (logger.isTraceEnabled()) {
         logger.trace("Getting BeanInfo for class [" + beanClass.getName() + "]");
      }
      //内省获取BeanInfo
      this.beanInfo = getBeanInfo(beanClass);

      if (logger.isTraceEnabled()) {
         logger.trace("Caching PropertyDescriptors for class [" + beanClass.getName() + "]");
      }
      this.propertyDescriptors = new LinkedHashMap<>();

      // This call is slow so we do it once.
      //获取beanClass中所有PropertyDescriptor（原生或spring增强的）
      PropertyDescriptor[] pds = this.beanInfo.getPropertyDescriptors();
      for (PropertyDescriptor pd : pds) {
         if (Class.class == beanClass && !("name".equals(pd.getName()) ||
               (pd.getName().endsWith("Name") && String.class == pd.getPropertyType()))) {
            // Only allow all name variants of Class properties
            continue;
         }
         if (URL.class == beanClass && "content".equals(pd.getName())) {
            // Only allow URL attribute introspection, not content resolution
            continue;
         }
         if (pd.getWriteMethod() == null && isInvalidReadOnlyPropertyType(pd.getPropertyType())) {
            // Ignore read-only properties such as ClassLoader - no need to bind to those
            continue;
         }
         if (logger.isTraceEnabled()) {
            logger.trace("Found bean property '" + pd.getName() + "'" +
                  (pd.getPropertyType() != null ? " of type [" + pd.getPropertyType().getName() + "]" : "") +
                  (pd.getPropertyEditorClass() != null ?
                        "; editor [" + pd.getPropertyEditorClass().getName() + "]" : ""));
         }
         /**
          * 增强PropertyDescriptor
          * GenericTypeAwarePropertyDescriptor是spring定义，继承了PropertyDescriptor
          * 添加了更多的方法，方便获取属性相关的信息
          */
         pd = buildGenericTypeAwarePropertyDescriptor(beanClass, pd);
         //缓存起来
         this.propertyDescriptors.put(pd.getName(), pd);
      }

      // Explicitly check implemented interfaces for setter/getter methods as well,
      // in particular for Java 8 default methods...
      Class<?> currClass = beanClass;
      while (currClass != null && currClass != Object.class) {
         //内省处理接口有默认实现的setter/getter方法
         introspectInterfaces(beanClass, currClass);
         currClass = currClass.getSuperclass();
      }

      this.typeDescriptorCache = new ConcurrentReferenceHashMap<>();
   }
   catch (IntrospectionException ex) {
      throw new FatalBeanException("Failed to obtain BeanInfo for class [" + beanClass.getName() + "]", ex);
   }
}
```

主要方法就是getBeanInfo方法

```java
private static BeanInfo getBeanInfo(Class<?> beanClass) throws IntrospectionException {
   /**
    * spring对原生的内省包装了一下
    * 返回的BeanInfo类型是spring自己定义的ExtendedBeanInfo，实现更强大的功能，获取更多的信息
    */
   for (BeanInfoFactory beanInfoFactory : beanInfoFactories) {
      BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanClass);
      if (beanInfo != null) {
         return beanInfo;
      }
   }
   //原生，使用的JDK中的Introspector工具类实现内省，返回SimpleBeanInfo，里面方法很少
   return (shouldIntrospectorIgnoreBeaninfoClasses ?
         Introspector.getBeanInfo(beanClass, Introspector.IGNORE_ALL_BEANINFO) :
         Introspector.getBeanInfo(beanClass));
}
```

Introspector.getBeanInfo(beanClass))中的逻辑就是上面内省机制的逻辑

最后会根据得到的内省结果得到PropertyDescriptor，获取对应的属性描述，然后调用resolveDependency在spring容器中找到这个属性对应的对象（最终的逻辑是调用beanFactory.getBean(beanName)来获取），然后把得到的对象结果和属性名称添加到MutablePropertyValues中

```java
pvs.add(propertyName, autowiredArgument);
```

最后等待applyPropertyValues(beanName, mbd, bw, pvs);统一赋值，到此autowireByType的逻辑大概结束了。



# 6.initializeBean 原理(Bean的初始化)

```java
protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
   //安全检查
   if (System.getSecurityManager() != null) {
      AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
         invokeAwareMethods(beanName, bean);
         return null;
      }, getAccessControlContext());
   }
   else {
      /**
       * 执行部分aware接口方法，注入容器内部的对象
       * 先后顺序为BeanNameAware，BeanClassLoaderAware，BeanFactoryAware
       */
      invokeAwareMethods(beanName, bean);
   }

   Object wrappedBean = bean;
   if (mbd == null || !mbd.isSynthetic()) {
      //执行部分Aware方法 和注解版 lifecycle init callback（比如@PostConstruct）
      //执行所有BeanPostProcessor的postProcessBeforeInitialization方法
      wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
   }

   try {
      // 执行 接口版 lifecycle init callback 比如 InitializingBean的 afterPropertiesSet()
      //还有xml或注解@Bean中的init初始化方法
      invokeInitMethods(beanName, wrappedBean, mbd);
   }
   catch (Throwable ex) {
      throw new BeanCreationException(
            (mbd != null ? mbd.getResourceDescription() : null),
            beanName, "Invocation of init method failed", ex);
   }
   if (mbd == null || !mbd.isSynthetic()) {
      //执行所有BeanPostProcessor的postProcessAfterInitialization方法
      /**
       * 完成aop---生成代理
       * 事件发布 监听
       */
      wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
   }

   return wrappedBean;
}
```

invokeAwareMethods方法执行部分aware方法

```java
private void invokeAwareMethods(String beanName, Object bean) {
   if (bean instanceof Aware) {
      if (bean instanceof BeanNameAware) {
         ((BeanNameAware) bean).setBeanName(beanName);
      }
      if (bean instanceof BeanClassLoaderAware) {
         ClassLoader bcl = getBeanClassLoader();
         if (bcl != null) {
            ((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
         }
      }
      if (bean instanceof BeanFactoryAware) {
         ((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
      }
   }
}
```



ApplicationContextAwareProcessor类中的postProcessBeforeInitialization方法执行了部分aware接口方法

```java
public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
   //未实现这些aware接口就什么也不做
   if (!(bean instanceof EnvironmentAware || bean instanceof EmbeddedValueResolverAware ||
         bean instanceof ResourceLoaderAware || bean instanceof ApplicationEventPublisherAware ||
         bean instanceof MessageSourceAware || bean instanceof ApplicationContextAware)){
      return bean;
   }

   AccessControlContext acc = null;

   if (System.getSecurityManager() != null) {
      acc = this.applicationContext.getBeanFactory().getAccessControlContext();
   }

   if (acc != null) {
      AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
         invokeAwareInterfaces(bean);
         return null;
      }, acc);
   }
   else {
      //执行Aware接口方法
      invokeAwareInterfaces(bean);
   }

   return bean;
}
```

```java
private void invokeAwareInterfaces(Object bean) {
   if (bean instanceof EnvironmentAware) {
      ((EnvironmentAware) bean).setEnvironment(this.applicationContext.getEnvironment());
   }
   if (bean instanceof EmbeddedValueResolverAware) {
      ((EmbeddedValueResolverAware) bean).setEmbeddedValueResolver(this.embeddedValueResolver);
   }
   if (bean instanceof ResourceLoaderAware) {
      ((ResourceLoaderAware) bean).setResourceLoader(this.applicationContext);
   }
   //ApplicationEventPublisher就是应用上下文对象
   if (bean instanceof ApplicationEventPublisherAware) {
      ((ApplicationEventPublisherAware) bean).setApplicationEventPublisher(this.applicationContext);
   }
   if (bean instanceof MessageSourceAware) {
      ((MessageSourceAware) bean).setMessageSource(this.applicationContext);
   }
   if (bean instanceof ApplicationContextAware) {
      ((ApplicationContextAware) bean).setApplicationContext(this.applicationContext);
   }
}
```

@PostConstruct执行流程

InitDestroyAnnotationBeanPostProcessor类的postProcessBeforeInitialization方法

```java
public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
   //获取所有生命周期元数据
   LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
   try {
      //执行生命周期初始化方法
      metadata.invokeInitMethods(bean, beanName);
   }
   catch (InvocationTargetException ex) {
      throw new BeanCreationException(beanName, "Invocation of init method failed", ex.getTargetException());
   }
   catch (Throwable ex) {
      throw new BeanCreationException(beanName, "Failed to invoke init method", ex);
   }
   return bean;
}
```

```java
private LifecycleMetadata findLifecycleMetadata(Class<?> clazz) {
   /**
    * 先查缓存中有没有生命周期元数据
    * this.lifecycleMetadataCache是一个map集合，它的key就是当前类的clazz
    * value是当前类的生命周期元数据
    */
   if (this.lifecycleMetadataCache == null) {
      // Happens after deserialization, during destruction...
      //直接去获取生命周期元数据，重点方法
      return buildLifecycleMetadata(clazz);
   }
   // Quick check on the concurrent map first, with minimal locking.
   LifecycleMetadata metadata = this.lifecycleMetadataCache.get(clazz);
   if (metadata == null) {
      synchronized (this.lifecycleMetadataCache) {
         metadata = this.lifecycleMetadataCache.get(clazz);
         if (metadata == null) {
            //直接去获取生命周期元数据
            metadata = buildLifecycleMetadata(clazz);
            //缓存，key就是当前类的clazz
            this.lifecycleMetadataCache.put(clazz, metadata);
         }
         return metadata;
      }
   }
   return metadata;
}
```

```java
private LifecycleMetadata buildLifecycleMetadata(final Class<?> clazz) {
   /**
    * this.initAnnotationType为PostConstruct.class
    * this.destroyAnnotationType为PreDestroy.class
    * 在CommonAnnotationBeanPostProcessor默认的构造方法中赋值
    * AnnotationUtils.isCandidateClass()是判断clazz中是否存在PostConstruct和PreDestroy注解
    */
   if (!AnnotationUtils.isCandidateClass(clazz, Arrays.asList(this.initAnnotationType, this.destroyAnnotationType))) {
      //不存在PostConstruct和PreDestroy注解，直接返回一个空的生命周期元数据
      return this.emptyLifecycleMetadata;
   }

   List<LifecycleElement> initMethods = new ArrayList<>();
   List<LifecycleElement> destroyMethods = new ArrayList<>();
   Class<?> targetClass = clazz;

   do {
      final List<LifecycleElement> currInitMethods = new ArrayList<>();
      final List<LifecycleElement> currDestroyMethods = new ArrayList<>();

      /**
       * ReflectionUtils.doWithLocalMethods()方法很简单，其实就是遍历targetClass所有的
       * 方法，将它作为参数回调接口方法。这个方法我在说@Autowired原理的时候已经详细解释过了，这里
       * 不在多费唇舌
       * 真正的处理逻辑在参数2的lamada表达式中
       */
      ReflectionUtils.doWithLocalMethods(targetClass, method -> {
         /****************************处理@PostConstruct注解******************************/
         //method.isAnnotationPresent()判断方法上有没有指定的注解（反射的知识）
         if (this.initAnnotationType != null && method.isAnnotationPresent(this.initAnnotationType)) {
            //构建LifecycleElement
            LifecycleElement element = new LifecycleElement(method);
            //加入到初始化方法集合中
            currInitMethods.add(element);
            if (logger.isTraceEnabled()) {
               logger.trace("Found init method on class [" + clazz.getName() + "]: " + method);
            }
         }
         /****************************处理@PreDestroy注解******************************/
         if (this.destroyAnnotationType != null && method.isAnnotationPresent(this.destroyAnnotationType)) {
            currDestroyMethods.add(new LifecycleElement(method));
            if (logger.isTraceEnabled()) {
               logger.trace("Found destroy method on class [" + clazz.getName() + "]: " + method);
            }
         }
      });

      initMethods.addAll(0, currInitMethods);
      destroyMethods.addAll(currDestroyMethods);
      //获取父类，因为父类中也有可能指定了生命周期方法
      targetClass = targetClass.getSuperclass();
   }
   while (targetClass != null && targetClass != Object.class);
   //返回声明周期元数据
   return (initMethods.isEmpty() && destroyMethods.isEmpty() ? this.emptyLifecycleMetadata :
         //构建LifecycleMetadata
         new LifecycleMetadata(clazz, initMethods, destroyMethods));
}
```

然后调用metadata.invokeInitMethods(bean, beanName);执行@PostConstruct标注的方法



```java
protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
      throws Throwable {

   boolean isInitializingBean = (bean instanceof InitializingBean);
   // 如果是InitializingBean并且有afterPropertiesSet，调用afterPropertiesSet方法
   if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
      if (logger.isTraceEnabled()) {
         logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
      }
      if (System.getSecurityManager() != null) {
         try {
            AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
               ((InitializingBean) bean).afterPropertiesSet();
               return null;
            }, getAccessControlContext());
         }
         catch (PrivilegedActionException pae) {
            throw pae.getException();
         }
      }
      else {
         ((InitializingBean) bean).afterPropertiesSet();
      }
   }

   // 如果是InitializingBean，但没有afterPropertiesSet，调用自定义的方法
   if (mbd != null && bean.getClass() != NullBean.class) {
      //获取初始化方法的名称
      String initMethodName = mbd.getInitMethodName();
      //bean指定了初始化方法
      if (StringUtils.hasLength(initMethodName) &&
            //并且不是InitializingBean的实例，指定的初始化方法也不是InitializingBean中的接口方法
            !(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
            //并且初始化方法也不是外部管理
            !mbd.isExternallyManagedInitMethod(initMethodName)) {
         //执行定制的初始化方法
         invokeCustomInitMethod(beanName, bean, mbd);
      }
   }
}
```

先执行InitializingBean接口的afterPropertiesSet方法，然后执行xml中或@Bean中的init初始化方法

最后applyBeanPostProcessorsAfterInitialization中是aop的逻辑，这里先省略。



