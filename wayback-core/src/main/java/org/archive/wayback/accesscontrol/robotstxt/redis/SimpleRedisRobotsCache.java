package org.archive.wayback.accesscontrol.robotstxt.redis;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.archive.wayback.accesscontrol.robotstxt.redis.RedisRobotsLogic.RedisValue;
import org.archive.wayback.core.Resource;
import org.archive.wayback.exception.LiveDocumentNotAvailableException;
import org.archive.wayback.exception.LiveWebCacheUnavailableException;
import org.archive.wayback.exception.LiveWebTimeoutException;
import org.archive.wayback.liveweb.LiveWebCache;
import org.archive.wayback.webapp.PerfStats;

import com.google.common.io.ByteStreams;

public class SimpleRedisRobotsCache implements LiveWebCache {
	
	private final static Logger LOGGER = Logger
	.getLogger(SimpleRedisRobotsCache.class.getName());
	
	enum PerfStat
	{
		RobotsRedis,
		RobotsLive,
	};
	
	/* REDIS */
	protected RedisRobotsLogic redisCmds;
	
	/* EXTERNAL CACHE */
	protected LiveWebCache liveweb = null;
	
	protected boolean gzipRobots = false;
	
	final static int STATUS_OK = 200;
	/**
	 * RobotsResult status code representing "unknown host" (aka NXDOMAIN)
	 * failure. While disposition is the same as HTTP 404 response, we
	 * assigned a distinct value so that NXDOMAIN can be distinguished from
	 * 404. (TODO: should have used a negative value? positive 4xx value is
	 * convenient because exclusion filter treats all 4xx as "no robots.txt")
	 * @see org.archive.wayback.accesscontrol.robotstxt.RobotExclusionFilter
	 */
	final static int STATUS_NXDOMAIN = 498;
	/**
	 * RobotsResult status code representing IOException while fetching robots.txt.
	 * Disposition of this situation is policy-dependent, but choice of 5xx value
	 * reflects commonly implemented behavior of search engines -- treat it
	 * as BLOCK-ALL until it resolves.
	 */
	final static int STATUS_IOERROR = 599;
	/**
	 * RobotsResult status code representing an other types of
	 * robots.txt fetch failure.
	 * Note this value is indistinguishable from 502 HTTP response
	 * from responsive target server. May want to revise this.
	 */
	final static int STATUS_ERROR = 502;
	/**
	 * old LiveWebCache did not communicate the specific statuscode
	 * of live-web fetch. All HTTP failures were marked as 502, and
	 * interpreted as "all allowed". Now that 5xx are "all disallow",
	 * this non-standard 4xx status code is used for backward
	 * compatibility.
	 */
	final static int STATUS_OLD_ERROR = 499;
	
	final static int MAX_ROBOTS_SIZE = 500000;
	
	final static int ONE_DAY = 60 * 60 * 24;

	private int totalTTL = ONE_DAY * 10;
	private int refreshTTL = ONE_DAY;

	private int notAvailTotalTTL = ONE_DAY * 2;
	private int notAvailRefreshTTL = ONE_DAY / 2;
	
	final static String ROBOTS_TOKEN_EMPTY = "0_ROBOTS_EMPTY";
	
	final static String ROBOTS_TOKEN_ERROR = "0_ROBOTS_ERROR-";
	final static String ROBOTS_TOKEN_ERROR_UNKNOWN = "0_ROBOTS_ERROR-0";
	
	final static String UPDATE_QUEUE_KEY = "robots_update_queue";
	final static int MAX_UPDATE_QUEUE_SIZE = 50000;

	// migration hack. until refreshTTL is elapsed since startup,
	// 502 errors in cache are not trusted and refreshed immediately.
	private final long startupTime;
	private boolean inMigrationPeriod = false;
	private static int MIN_UPDATE_INTERVAL = 30 * 60;
	private final static String ROBOTS_OLD_TOKEN_ERROR = ROBOTS_TOKEN_ERROR + STATUS_ERROR;

	public void setInMigrationPeriod(boolean inMigrationPeriod) {
		this.inMigrationPeriod = inMigrationPeriod;
	}
	public boolean isInMigrationPeriod() {
		return inMigrationPeriod;
	}
	
	public SimpleRedisRobotsCache() {
		this.startupTime = System.currentTimeMillis();
	}

	@Override
	public Resource getCachedResource(URL urlURL, long maxCacheMS,
				boolean bUseOlder) throws LiveDocumentNotAvailableException,
				LiveWebCacheUnavailableException, LiveWebTimeoutException,
				IOException {
		
		String url = urlURL.toExternalForm();
		
		RedisValue value = null;
		
		try {
			PerfStats.timeStart(PerfStat.RobotsRedis);
			
			if (redisCmds != null) {
				value = redisCmds.getValue(url);
			}
		} catch (LiveWebCacheUnavailableException lw) {
			value = null;
		} finally {
			PerfStats.timeEnd(PerfStat.RobotsRedis);
		}

		// migration hack - don't trust cached 502 during migration period.
		// old code records 404 as 502, which results in false blockage with
		// new code. prevent too frequent updates.
		if (value != null && inMigrationPeriod) {
			if (ROBOTS_OLD_TOKEN_ERROR.equals(value.value) &&
					(notAvailTotalTTL - value.ttl) > MIN_UPDATE_INTERVAL) {
				long elapsedSec = (System.currentTimeMillis() - startupTime) / 1000;
				if (elapsedSec < refreshTTL) {
					value = null;
				}
			}
		}

		// Use the old liveweb cache, if provided
		if (value == null) {
			RobotsResult result = loadExternal(urlURL, maxCacheMS, bUseOlder);
			
			PerfStats.timeStart(PerfStat.RobotsRedis);
			this.updateCache(result.robots, url, null, result.status, true);
			PerfStats.timeEnd(PerfStat.RobotsRedis);
			
			if (result == null || result.status != STATUS_OK) {
				throw new LiveDocumentNotAvailableException(urlURL, result.status);
			}
			
			return new RobotsTxtResource(result.robots);
			
		} else {
			
			if (isExpired(value, url, 0)) {
				PerfStats.timeStart(PerfStat.RobotsRedis);				
				redisCmds.pushKey(UPDATE_QUEUE_KEY, url, MAX_UPDATE_QUEUE_SIZE);
				PerfStats.timeEnd(PerfStat.RobotsRedis);
			}
			
			String currentRobots = value.value;
			
			if (currentRobots.startsWith(ROBOTS_TOKEN_ERROR)) {
				int status;
				try {
					status = Integer.parseInt(currentRobots.substring(ROBOTS_TOKEN_ERROR.length()));
				} catch (NumberFormatException ex) {
					status = 0;
				}
				throw new LiveDocumentNotAvailableException(urlURL, status);
			} else if (value.equals(ROBOTS_TOKEN_EMPTY)) {
				currentRobots = "";
			}
			
			return new RobotsTxtResource(currentRobots);	
		}
	}

	@Override
	public void shutdown() {
		if (redisCmds != null) {
			redisCmds.close();
			redisCmds = null;
		}
	}
	
	static boolean isFailedError(int status)
	{
		return (status == 0) || ((status >= 500));
	}
	
	static boolean isRedirect(int status)
	{
		return (status == 301) || (status == 302);
	}
	
	static boolean isFailedError(String code)
	{
		try {
			int status = Integer.parseInt(code);
			return isFailedError(status);
		} catch (NumberFormatException n) {
			return true;
		}
	}
	
	public boolean isExpired(RedisValue value, String url, int customRefreshTime) {
		
		int maxTime, refreshTime;
		
		boolean isFailedError = value.value.startsWith(ROBOTS_TOKEN_ERROR);
		
		if (isFailedError) {
			String code = value.value.substring(ROBOTS_TOKEN_ERROR.length());
			isFailedError = isFailedError(code);
		}
		
		if (isFailedError) {
			maxTime = notAvailTotalTTL;
			refreshTime = notAvailRefreshTTL;
		} else {
			maxTime = totalTTL;
			refreshTime = refreshTTL;
		}
		
		if (customRefreshTime > 0) {
			refreshTime = customRefreshTime;
		}
		
		if ((maxTime - value.ttl) >= refreshTime) {
//			LOGGER.info("Queue for robot refresh: "
//					+ (maxTime - value.ttl) + ">=" + refreshTime + " " + url);
			
			return true;
		}
		
		return false;
	}
	
	class RobotsResult
	{
		String oldRobots;
		String robots;
		int status;
		
		RobotsResult(String oldRobots)
		{
			status = 0;
			this.oldRobots = oldRobots;
			robots = null;
		}
		
		RobotsResult(String robots, int status)
		{
			this.robots = robots;
			this.status = status;
		}
		
		boolean isSameRobots() {
			// Note: oldRobots is ROBOTS_TOKEN_ERROR + <status> if failure was
			// cached, whereas robots == null if status != 200.
			if (robots == null) {
				// new robots.txt is a failure. compare status.
				if (oldRobots != null && oldRobots.startsWith(ROBOTS_TOKEN_ERROR)) {
					int oldStatus;
					try {
						oldStatus = Integer.parseInt(oldRobots
							.substring(ROBOTS_TOKEN_ERROR.length()));
					} catch (NumberFormatException ex) {
						oldStatus = 0;
					}
					return status == oldStatus;
				}
				// no cached robots.txt or 200 -> different.
				return false;
			} else {
				return robots.equals(oldRobots);
			}
		}
	}
	
	protected RobotsResult loadExternal(URL urlURL, long maxCacheMS, boolean bUseOlder)
	{
		//RobotsContext context = new RobotsContext(url, current, true, true);
		
		Resource origResource = null;
		int status = 0;
		String contents = null;
		
		try {
			PerfStats.timeStart(PerfStat.RobotsLive);
			
			origResource = liveweb.getCachedResource(urlURL, maxCacheMS, bUseOlder);
			
			status = origResource.getStatusCode();
			
			if (status == STATUS_OK) {	
				if (origResource instanceof RobotsTxtResource) {
					contents = ((RobotsTxtResource)origResource).getContents();
				} else {
					contents = IOUtils.toString(ByteStreams.limit(origResource, MAX_ROBOTS_SIZE), "UTF-8");
				}
			}
		} catch (LiveDocumentNotAvailableException ex) {
			// InstaLiveWeb.getCachedResource() throws LDNAE when robots.txt resulted
			// in either non-2xx responses or IOException from HTTP request (unknown host,
			// connection refused/timeout, socket timeout/closed etc.) We need to translate
			// it into different status codes. Better approach may be storing exception in
			// RobotsResult, but RobotsResult cannot be passed to exclusion filters in
			// current Wayback.
			status = ex.getOriginalStatuscode();
			if (status == 0) {
				// status == 0 is from old version of InstaLiveWeb. map it to
				// a special status code for it.
				LOGGER.log(Level.WARNING, "Failed to fetch (old status value 0) " + urlURL, ex);
				status = STATUS_OLD_ERROR;
			} else if (status > 0) {
				// HTTP failure response - use status as is in RobotsResult
				LOGGER.log(Level.INFO, "Failed to fetch " + urlURL + ": " + ex);
			} else {
				Throwable th = ex.getCause();
				if (th != null) {
					LOGGER.info("Failed to fetch " + urlURL + ": " + th);
				} else {
					LOGGER.log(Level.WARNING, "Failed to fetch (cause unavailable) " + urlURL, ex);
				}
				if (th instanceof UnknownHostException) {
					// Host no longer exists. In robots.txt exclusion, disposition is
					// different from other type of fetch errors. Use a special status
					// code representing this situation.
					status = STATUS_NXDOMAIN;
				} else if (th instanceof IOException) {
					status = STATUS_IOERROR;
				} else {
					status = STATUS_ERROR;
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.INFO, "Liveweb fetch failed for " + urlURL, e);
			status = STATUS_ERROR;
		} finally {
			if (origResource != null) {
				try {
					origResource.close();
				} catch (IOException e) {
					
				}
			}
			PerfStats.timeEnd(PerfStat.RobotsLive);
		}
		
		return new RobotsResult(contents, status);
	}
	
	protected void updateCache(final String contents, final String url, final String current, int status, boolean cacheFails) {		
		
		String newRedisValue = null;
		int newTTL = 0;
		boolean ttlOnly = false;
		
		if (status == STATUS_OK) {
			//contents = context.getNewRobots();
			newTTL = totalTTL;
			
			if (contents == null || contents.isEmpty()) {
				newRedisValue = ROBOTS_TOKEN_EMPTY;
			} else if (contents.length() > MAX_ROBOTS_SIZE) {
				newRedisValue = contents.substring(0, MAX_ROBOTS_SIZE);
			} else {
				newRedisValue = contents;
			}
			
		} else {			
			if (isFailedError(status)) {
				newTTL = notAvailTotalTTL;
				
				// Only Cacheing successful lookups
				if (!cacheFails) {
					return;
				}
			} else {
				newTTL = totalTTL;
			}
			
			newRedisValue = ROBOTS_TOKEN_ERROR + status;
		}
		
		String currentValue = current;	
		
		if (currentValue != null) {
			if (currentValue.equals(newRedisValue)) {
				ttlOnly = true;
			}
			
			// Don't override a valid robots with a timeout error
			if (!isRedirect(status) && !isValidRobots(newRedisValue) && isValidRobots(currentValue)) {
				newTTL = totalTTL;
				ttlOnly = true;
				if (LOGGER.isLoggable(Level.INFO)) {
					LOGGER.info("REFRESH ERROR: " + status + " - Keeping same robots for " + url);
				}
			}
		}
				
		final RedisValue value = new RedisValue((ttlOnly ? null : newRedisValue), newTTL);
		
		redisCmds.updateValue(url, value, gzipRobots);
	}
	
	protected boolean isValidRobots(String value) {
		return !value.startsWith(ROBOTS_TOKEN_ERROR) && !value.equals(ROBOTS_TOKEN_EMPTY);
	}
	
	public RobotsResult forceUpdate(String url, int minUpdateTime, boolean cacheFails)
	{
		String current = null;
		
		try {
			RedisValue value = null;
			
			try {
				PerfStats.timeStart(PerfStat.RobotsRedis);
				value = redisCmds.getValue(url);
			} finally {
				PerfStats.timeEnd(PerfStat.RobotsRedis);
			}
			
			// Just in case, avoid too many updates
			if ((minUpdateTime > 0) && (value != null) && !isExpired(value, url, minUpdateTime)) {
				return new RobotsResult(value.value);
			}
			
			current = (value != null ? value.value : null);
		} catch (LiveWebCacheUnavailableException lw) {
			current = lw.toString();
		}
		
		RobotsResult result = null;
	
		try {
			result = loadExternal(new URL(url), 0, false);
		} catch (MalformedURLException e) {
			return new RobotsResult(current);
		}
		result.oldRobots = current;

		if (result.status == STATUS_OK || !result.isSameRobots() || cacheFails) {
			this.updateCache(result.robots, url, current, result.status, cacheFails);
			
//			if (LOGGER.isLoggable(Level.INFO)) {
//				LOGGER.info("Force updated: " + url);
//			}
		}
		
		return result;
	}

//	public RedisRobotsLogic getRedisConnMan() {
//		return redisCmds.;
//	}

	public void setRedisConnMan(RedisConnectionManager redisConn) {
		this.redisCmds = new RedisRobotsLogic(redisConn);
	}

	public void setRedisCmds(RedisRobotsLogic redisCmds) {
		this.redisCmds = redisCmds;
	}

	public LiveWebCache getLiveweb() {
		return liveweb;
	}

	public void setLiveweb(LiveWebCache liveweb) {
		this.liveweb = liveweb;
	}

	public boolean isGzipRobots() {
		return gzipRobots;
	}

	public void setGzipRobots(boolean gzipRobots) {
		this.gzipRobots = gzipRobots;
	}

	public int getTotalTTL() {
		return totalTTL;
	}

	public void setTotalTTL(int totalTTL) {
		this.totalTTL = totalTTL;
	}

	public int getNotAvailTotalTTL() {
		return notAvailTotalTTL;
	}

	public void setNotAvailTotalTTL(int notAvailTotalTTL) {
		this.notAvailTotalTTL = notAvailTotalTTL;
	}

}
