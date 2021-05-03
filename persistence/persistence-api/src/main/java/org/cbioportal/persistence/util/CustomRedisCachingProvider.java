/*
 * Copyright (c) 2020 Memorial Sloan-Kettering Cancer Center.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY, WITHOUT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY OR FITNESS
 * FOR A PARTICULAR PURPOSE. The software and documentation provided hereunder
 * is on an "as is" basis, and Memorial Sloan-Kettering Cancer Center has no
 * obligations to provide maintenance, support, updates, enhancements or
 * modifications. In no event shall Memorial Sloan-Kettering Cancer Center be
 * liable to any party for direct, indirect, special, incidental or
 * consequential damages, including lost profits, arising out of the use of this
 * software and its documentation, even if Memorial Sloan-Kettering Cancer
 * Center has been advised of the possibility of such damage.
 */

/*
 * This file is part of cBioPortal.
 *
 * cBioPortal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cbioportal.persistence.util;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.jcache.JCachingProvider;
import org.redisson.jcache.configuration.RedissonConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.spi.CachingProvider;
import java.util.concurrent.TimeUnit;

public class CustomRedisCachingProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CustomRedisCachingProvider.class);

    @Value("${persistence.cache_type:no-cache}")
    private String cacheType;

    @Value("${app.name:cbioportal}")
    private String appName;

    @Value("${redis.leader_address}")
    private String leaderAddress;

    @Value("${redis.follower_address}")
    private String followerAddress;

    @Value("${redis.database}")
    private Integer database;

    @Value("${redis.password}")
    private String password;
    
    @Value("${redis.expiry:21600000}")
    private Long expiryMs;

    public RedissonClient getRedissonClient() {
        Config config = new Config();
        LOG.debug("leaderAddress: " + leaderAddress);
        LOG.debug("followerAddress: " + followerAddress);
        config.useMasterSlaveServers()
                .setMasterAddress(leaderAddress)
                .addSlaveAddress(followerAddress)
                .setDatabase(database)
                .setPassword(password);
        
        RedissonClient redissonClient = Redisson.create(config);
        LOG.debug("Created Redisson Client: " + redissonClient);
        return redissonClient;
    }

    public CacheManager getCacheManager(RedissonClient redissonClient) {
        LOG.debug("in getCacheManager");

        MutableConfiguration<String, String> jcacheConfig = new MutableConfiguration<>();
        Configuration<String, String> config = RedissonConfiguration.fromInstance(redissonClient, jcacheConfig);

        Factory<ExpiryPolicy> expiryPolicyFactory = 
            CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MILLISECONDS, expiryMs));
        jcacheConfig.setExpiryPolicyFactory(expiryPolicyFactory);
        Configuration<String, String> configWithExpiry = RedissonConfiguration.fromInstance(redissonClient, jcacheConfig);
        
        CachingProvider redisCachingProvider = null;
        LOG.debug("loop through caching providers");
        for (CachingProvider cachingProvider : Caching.getCachingProviders()) {
            LOG.debug("CachingProvider: " + cachingProvider);
            if (cachingProvider instanceof JCachingProvider) {
                redisCachingProvider = cachingProvider;
                break;
            }
        }
        if (redisCachingProvider == null) {
            // this should never happen, no one should try to create this bean (calling this method)
            // unless we have loaded the Redis libraries
            LOG.error("Failed to find a Redis caching provider");
            return null;
        }
        CacheManager manager = redisCachingProvider.getCacheManager();
        manager.createCache(appName + "StaticRepositoryCacheOne", config);
        manager.createCache(appName + "GeneralRepositoryCache", configWithExpiry);
    
        // Evict cache to ensure new data is pulled
        // Specific to Redis Impl because Redis cache sits in external db and not in memory
        manager.getCache(appName + "GeneralRepositoryCache").clear();
        manager.getCache(appName + "StaticRepositoryCacheOne").clear();
       
        return manager;
    }
}
