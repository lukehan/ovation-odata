package ovation.odata.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;

import ovation.DataContext;
import ovation.Ovation;
import ovation.OvationException;
import ovation.UserAuthenticationException;

/**
 * a optionally fixed-size time-out cache of DataContexts keyed by a String to be generated by the caller
 * (and presumably stored in a cookie, session, or other use-specific store) by which the DataContext
 * can be later retrieved.
 * 
 * configured via the props:<ul>
 * 	<li>ovodata.DataContext.cacheExpiryMs	- number of milliseconds at which a DataContext expires if it isn't accessed (-1 = off)</li>
 *  <li>ovodata.DataContext.cacheMaxSize	- max number of DataContexts to allow concurrently (-1 = off)</li>
 *  <li>ovodata.DataContext.file       		- default Objectivity/DB data file</li>
 *  <li>ovodata.DataContext.file.[user]		- optional user-specific override for default data file</li>
 * </ul>
 * 
 * @author Ron
 */
public class DataContextCache {
	/** last-access added to track DataContexts which haven't been used in a while (have expired) */
	private static class DataContextWithLastAccess {
		DataContext _context;
		long		_lastAccess;
		DataContextWithLastAccess(DataContext context) { _context = context; _lastAccess = System.currentTimeMillis(); }
	}
	
	public  static final Logger 	_log = Logger.getLogger(DataContextCache.class);
	private static final Properties _props = PropertyManager.getProperties(DataContextCache.class);

	/** maximum number of concurrent DataContexts; -1 to disable limiting */
	private static final int _maxSize = Props.getProp(_props, Props.DC_MAX_CACHE_SIZE, -1);

	/** maximum number of milliseconds to allow a DataContext to sit idle before closing it; 
	 * -1 to disable DataContext time-out; 0 to disable caching and close DataContexts as soon 
	 * as they are removed from their associated Thread.
	 */
	private static final long _expiryMs = Props.getProp(_props, Props.DC_EXPIRY_MS, Props.DC_EXPIRY_MS_DEFAULT);

	/** allows DataContexts to be associated with a specific (and single) Thread */
	private static final ThreadLocal<DataContext> _threadContext = new ThreadLocal<DataContext>();
	
	/**
	 * gets the DataContext associated with the calling thread (if any) - this allows the DataContext to be
	 * associated with a thread in the authentication layer but be retrievable by any code invoked by that
	 * thread without having to pass it as an argument.
	 * @return the calling Thread's DataContext or null if none is associated with the Thread
	 */
	public static DataContext getThreadContext() { 
		return _threadContext.get(); 
	}

	/** 
	 * attaches a DataContext to the current processing thread
	 * @param ctx - if non-null is associated with the Thread; if null current thread's context is removed
	 * and if caching is disabled (ovodata.DataContext.cacheExpiryMs=0) then context is immediately closed
	 */
	public static void setThreadContext(DataContext ctx) 	{ 
		if (ctx != null) {
			_threadContext.set(ctx); 
		} else {
			// if caching is disabled close the context immediately
			if (_expiryMs == 0) {
				DataContext oldCtx = getThreadContext();
				if (oldCtx != null) {
					//oldCtx.close();
				}
			}
			_threadContext.remove(); 
		}
	}
	
	/**
	 * LinkedHashMap has a capping feature which makes it easier to limit its size.
	 * we use the log ctor with default initial-cap and load-factor so can specify access-ordering
	 * so that the eldest entry is the least-most-recently-accessed rather than 
	 * least-most-recently-inserted (the default behavior) - otherwise we have to
	 * re-put() the entry every time we get() it to keep it at the head of the list
	 */
	private static final LinkedHashMap<String, DataContextWithLastAccess> _contextMap = 
			new LinkedHashMap<String, DataContextWithLastAccess>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		protected boolean removeEldestEntry(Map.Entry<String, DataContextWithLastAccess> eldest) {
			return (_maxSize != -1 && size() > _maxSize);
		}
	};
	
	/**
	 * should be called on shutdown of system to cleanly close out all DataContexts
	 */
	public synchronized static void close() {
		for (DataContextWithLastAccess context : _contextMap.values()) {
			try {
				//context._context.close();
			} catch (Throwable t) {
				_log.error("while closing " + context + " - " + t, t);
			}
		}
		_contextMap.clear();
	}

	/**
	 * create a new DataContext, closing and overwriting the old if any, for the given key.
	 * 
	 * @param uid 		user ID
	 * @param pwd 		password
	 * @param filePath	path to Objectivity/DB database file
	 * @param key		unique key under which DataContext is to be stored/retrieved
	 * @return newly-created DataContext
	 * @throws OvationException
	 * @throws UserAuthenticationException
	 */
	public synchronized static DataContext getDataContext(String uid, String pwd, String filePath, String key) 
			throws OvationException, UserAuthenticationException {
			    
		if (getDataContext(key) != null) {
			// close old context with this key, if any
			closeDataContext(key);
		}
		DataContext context = Ovation.connect(filePath, uid, pwd);
		if (_expiryMs != 0) {	// set expiry-ms to 0 to disable caching entirely
			_contextMap.put(key, new DataContextWithLastAccess(context));
		}
		return context;
	}

	/**
	 * calls below version with uid as the key (maybe the key idea wasn't such a good one)
	 * @param uid
	 * @param pwd
	 * @return
	 * @throws OvationException
	 * @throws UserAuthenticationException
	 */
	public static DataContext getDataContext(String uid, String pwd) throws OvationException, UserAuthenticationException {
		return getDataContext(uid, pwd, uid);
	}
	
	/**
	 * create a new DAtaContext, closing and overwriting the old if any, for the given key.  
	 * file path is pulled from props (either user-specific or default)
	 * 
	 * @param uid
	 * @param pwd
	 * @param key
	 * @return
	 * @throws OvationException
	 * @throws UserAuthenticationException
	 */
	public synchronized static DataContext getDataContext(String uid, String pwd, String key) 
			throws OvationException, UserAuthenticationException {
		String filePath = getODBFilePath(uid);
		if (filePath == null) {
			throw new OvationException("not properly configured - no default file path specified (ovodata.DataContext.file)");
		}
		return getDataContext(uid, pwd, filePath, key);
	}

	/**
	 * @param key - authentication token or other method for IDing specific uid/pwd Context (already created)
	 * @return DataContext associated with key or <code>null</code> if none found
	 */
	public synchronized static DataContext getDataContext(String key) {
		DataContextWithLastAccess contextData = _contextMap.get(key);
		if (contextData == null) {
			return null;
		}
		contextData._lastAccess = System.currentTimeMillis();
		return contextData._context;
	}
	
	/**
	 * used when a user logs out (if possible) or when DataContext expires
	 * note, can't be called within loop that iterates _contextMap as it modifies _contextMap (ConcurrentModException)
	 * @param key
	 */
	public synchronized static void closeDataContext(String key) {
		DataContextWithLastAccess contextData = _contextMap.remove(key);
		if (contextData != null) {
			//contextData._context.close();
		}
	}
	
	/**
	 * @param uid - user ID
	 * @return user-specific "ovodata.DataContext.file.<uid>" if found, otherwise "ovodata.DataContext.file" or null
	 */
	public static String getODBFilePath(String uid) {
		return _props.getProperty(Props.DC_FILE_BASE + uid, _props.getProperty(Props.DC_FILE_DEFAULT, null));
	}
	
	/**
	 * remove any DataContexts which haven't been accessed in prop["ovodata.DataContext.expiryMs"] milliseconds
	 */
	public synchronized static void cullExpiredDataContexts() {
		long cutoff = System.currentTimeMillis() - _expiryMs;
		String[] keys = _contextMap.keySet().toArray(new String[_contextMap.size()]);	// deep-copy the key-set to avoid ConcurrentModEx
		for (String key : keys) {
			DataContextWithLastAccess data = _contextMap.get(key);
			if (data._lastAccess < cutoff) {
				closeDataContext(key);
			}
		}
	}
}