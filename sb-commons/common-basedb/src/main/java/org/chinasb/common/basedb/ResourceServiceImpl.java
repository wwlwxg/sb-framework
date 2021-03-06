package org.chinasb.common.basedb;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.chinasb.common.basedb.annotation.Resource;
import org.chinasb.common.utility.PackageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基础数据服务实现
 * 
 * @author zhujuan
 */
@Component
public class ResourceServiceImpl
		implements ResourceService, ApplicationListener<ContextRefreshedEvent> {
	private static final Logger LOGGER = LoggerFactory.getLogger(ResourceServiceImpl.class);
	/**
	 * 刷新事件标志
	 */
	private static final AtomicBoolean REFRESH_EVENT_SWITCH = new AtomicBoolean(false);
	/**
	 * 数据存储集合
	 */
	@SuppressWarnings("rawtypes")
	private final ConcurrentHashMap<Class, Storage> storages =
			new ConcurrentHashMap<Class, Storage>(64);
	/**
	 * 监听器列表
	 */
	private final List<ResourceListener> listeners = new ArrayList<ResourceListener>();

	/**
	 * 是否处理Spring重载事件
	 */
	@Autowired(required = false)
	@Qualifier("spring_refresh_event_reload")
	private Boolean springRefreshEventReload = false;

	/**
	 * 基础数据表所在的路径
	 */
	@Autowired(required = false)
	@Qualifier("basedb_location")
	private String resourceLocation = "res_db" + File.separator;

	/**
	 * 基础数据类所在的包
	 */
	@Autowired(required = false)
	@Qualifier("basedb_package")
	private String resourcePackage = "org.chinasb.**.basedb.model";

	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * 基础数据初始化
	 */
	private void initialize() {
		registerListener();
		Collection<Class<?>> clazzCollection =
				PackageUtils.scanPackages(resourcePackage);
		if (clazzCollection != null && !clazzCollection.isEmpty()) {
			for (Class<?> clazz : clazzCollection) {
				try {
					if (clazz.isAnnotationPresent(Resource.class)) {
						initializeStorage(clazz);
					}
				} catch (Exception e) {
					FormattingTuple message =
							MessageFormatter.format("加载  {} 基础数据时出错!", clazz.getName());
					LOGGER.error(message.getMessage(), e);
				}
			}
		} else {
			FormattingTuple message = MessageFormatter.format("在 {} 包下没有扫描到实体类!", resourcePackage);
			LOGGER.error(message.getMessage());
		}
		fireBasedbReload();
		LOGGER.info("基础数据加载完毕...");
	}

	/**
	 * 基础数据监听器重载
	 */
	private void fireBasedbReload() {
		if (listeners != null && !listeners.isEmpty()) {
			for (ResourceListener listener : listeners) {
				try {
					listener.onBasedbReload();
				} catch (Exception e) {
					LOGGER.error("BasedbListener 出错!", e);
				}
			}
		}
	}

	/**
	 * 注册监听器
	 */
	private void registerListener() {
		Map<String, ResourceListener> listenerMap =
				applicationContext.getBeansOfType(ResourceListener.class);
		if (listenerMap != null) {
			for (ResourceListener listener : listenerMap.values()) {
				listeners.add(listener);
			}
		}
	}

	/**
	 * 基础数据初始化
	 * 
	 * @param clazz
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void initializeStorage(Class clazz) {
		Storage storage = storages.get(clazz);
		if (storage == null) {
			storage = new Storage(clazz, resourceLocation, applicationContext);
			storages.putIfAbsent(clazz, storage);
			storage = storages.get(clazz);
		}
		storage.reload();
	}

	/**
	 * 获取基础数据存储对象
	 * 
	 * @param clazz
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	private Storage getStorage(Class clazz) {
		return storages.get(clazz);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public <T> T get(Object id, Class<T> clazz) {
		Storage storage = getStorage(clazz);
		if (storage != null) {
			return (T) storage.get(id);
		}
		return null;
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public <T> List<T> listByIndex(String indexName, Class<T> clazz, Object... indexValues) {
		Storage storage = getStorage(clazz);
		if (storage != null) {
			return Collections.unmodifiableList(storage.getByIndex(indexName, indexValues));
		}
		return Collections.emptyList();
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public <T, PK> List<PK> listIdByIndex(String indexName, Class<T> clazz, Class<PK> pk,
			Object... indexValues) {
		Storage storage = getStorage(clazz);
		if (storage != null) {
			return Collections.unmodifiableList(storage.getIndexIdList(indexName, indexValues));
		}
		return Collections.emptyList();
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public <T> T getByUnique(String indexName, Class<T> clazz, Object... indexValues) {
		Storage storage = getStorage(clazz);
		if (storage != null) {
			List<T> list = storage.getByIndex(indexName, indexValues);
			return list != null && !list.isEmpty() ? list.get(0) : null;
		}
		return null;
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public <T> List<T> listAll(Class<T> clazz) {
		Storage storage = getStorage(clazz);
		if (storage != null) {
			return Collections.unmodifiableList(storage.listAll());
		}
		return Collections.emptyList();
	}

	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	public <T> void addToIndex(String indexName, Object id, Class<T> clazz) {
		Storage storage = getStorage(clazz);
		if (storage == null) {
			return;
		}
		Map<String, List<Object>> indexTable = storage.getIndexTable();
		if (indexTable == null) {
			return;
		}
		String newIndexKey = KeyBuilder.buildIndexKey(clazz, indexName);
		List<Object> idList = indexTable.get(newIndexKey);
		if (idList == null) {
			idList = new ArrayList<Object>();
			indexTable.put(newIndexKey, idList);
		}
		if (!idList.contains(id)) {
			idList.add(id);
		}
	}

	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	public <T> void addToIndex(String indexName, Object id, Class<T> clazz, Object... indexValues) {
		Storage storage = getStorage(clazz);
		if (storage == null) {
			return;
		}
		String newIndexKey = KeyBuilder.buildIndexKey(clazz, indexName, indexValues);
		Map<String, List<Object>> indexTable = storage.getIndexTable();
		if (indexTable == null) {
			return;
		}
		List<Object> idList = indexTable.get(newIndexKey);
		if (idList == null) {
			idList = new ArrayList<Object>();
			indexTable.put(newIndexKey, idList);
		}
		if (!idList.contains(id)) {
			idList.add(id);
		}
	}

	@Override
	@SuppressWarnings("rawtypes")
	public void reloadAll() {
		for (Class clazz : storages.keySet()) {
			try {
				initializeStorage(clazz);
			} catch (Exception e) {
				FormattingTuple message = MessageFormatter.format("加载 {} 类时出错!", clazz.getName());
				LOGGER.error(message.getMessage(), e);
			}
		}
		fireBasedbReload();
	}

	/**
	 * 内部辅助类用于构建索引键
	 * <p>
	 * 通用key构建规则： className&indexName#indexValue[0]^indexValue[1]
	 * <li>className : 类的全限定名  如:com.my9yu.BaseModel </li>
	 * <li>indexName : 索引名  如: level </li>
	 * <li>indexValue : 索引值数组  如: [1,2,3] </li>
	 * </p>
	 */
	public static class KeyBuilder {
		public static String buildIndexKey(Class<?> clazz, String indexName, Object... indexValues){
			StringBuilder builder = new StringBuilder();
			if(clazz != null){
				builder.append(clazz.getName()).append("&");
			}
			if(indexName != null){
				builder.append(indexName).append("#");
			}
			if(indexValues != null && indexValues.length > 0){
				builder.append(StringUtils.arrayToDelimitedString(indexValues, "^"));
			}
			return builder.toString();
		}
	}
	
	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		if (!springRefreshEventReload.booleanValue() && REFRESH_EVENT_SWITCH.get()) {
			return;
		}
		REFRESH_EVENT_SWITCH.set(true);
		try {
			initialize();
		} catch (Exception e) {
			LOGGER.error("基础数据初始化出错!", e);
		}
		applicationContext.publishEvent(new ResourceReloadEvent(applicationContext));
	}
}
