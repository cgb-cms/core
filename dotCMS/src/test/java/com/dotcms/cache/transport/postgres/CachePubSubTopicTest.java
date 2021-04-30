package com.dotcms.cache.transport.postgres;

import org.junit.BeforeClass;
import org.junit.Test;
import com.dotcms.cache.transport.postgres.CachePubSubTopic.CacheEventType;
import com.dotcms.dotpubsub.DotPubSubEvent;
import com.dotcms.dotpubsub.DotPubSubProvider;
import com.dotcms.dotpubsub.DotPubSubProviderLocator;
import com.dotcms.dotpubsub.DotPubSubTopic;
import com.dotcms.dotpubsub.NullDotPubSubProvider;

public class CachePubSubTopicTest {

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        // override the provider for testing
        System.setProperty(DotPubSubProviderLocator.DEFAULT_DOT_PUBSUB_PROVIDER, NullDotPubSubProvider.class.getCanonicalName());
        
    }

    /**
     * tests if we can use a property to override the default provider
     */
    @Test
    public void test_provider_override() {
        DotPubSubProvider provider = DotPubSubProviderLocator.provider.get();
        assert provider instanceof NullDotPubSubProvider;

    }

    @Test
    public void test_provider_topic_sending__a_ping_gets_a_pong() {
        NullDotPubSubProvider provider = (NullDotPubSubProvider) DotPubSubProviderLocator.provider.get();
        DotPubSubEvent event= new DotPubSubEvent.Builder().withType(CacheEventType.PING.name()).build();
        DotPubSubTopic topic = new CachePubSubTopic("fakeServer");
        topic.notify(event);
        assert "PONG".equalsIgnoreCase(provider.lastEvent.getType());
        
    }
    
    
}