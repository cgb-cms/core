package com.dotcms.dotpubsub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.common.db.DotConnect;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.impossibl.postgres.api.jdbc.PGConnection;
import com.impossibl.postgres.api.jdbc.PGNotificationListener;
import com.zaxxer.hikari.HikariDataSource;
import io.vavr.Lazy;
import io.vavr.control.Try;

public class PostgresPubSubImpl implements DotPubSubProvider {

    private enum RUNSTATE {
        STOPPED, STARTED, REBUILD
    }



    public final String serverId;
    private long restartDelay=0;

    /**
     * provides db connection information for the postgres pub/sub connection
     */
    private Lazy<PgNgDataSourceUrl> attributes = Lazy.of(() -> getDatasourceAttributes());
    private AtomicReference<RUNSTATE> state = new AtomicReference<>(RUNSTATE.STOPPED);
    private PGConnection connection;

    /**
     * This is the list of topics that are subscribed to by the postgres pub/sub connection
     */
    private Map<Comparable<String>, DotPubSubTopic> topicMap = new ConcurrentHashMap<>();

    @VisibleForTesting
    protected static DotPubSubEvent lastEventIn, lastEventOut;


    @Override
    public DotPubSubProvider start() {

        int numberOfServers = Try.of(() -> APILocator.getServerAPI().getAliveServers().size()).getOrElse(1);
        Logger.info(PostgresPubSubImpl.class, () -> "Initing PostgresPubSub. Have servers:" + numberOfServers);
        
        listen();
        
        return this;
    }


    public PostgresPubSubImpl() {
        this(APILocator.getServerAPI().readServerId());


    }

    public PostgresPubSubImpl(String serverId) {
        this.serverId = APILocator.getShortyAPI().shortify(serverId);

    }

    /**
     * This is the DB Listener that listens for messages and then passes them on to the matching
     * DotPubSubTopic for processing by the topic.notify(DotPubSubEvent) method. This listener will
     * reconnect in the case of any errors or if the connection gets closed
     * 
     */
    private PGNotificationListener listener = new PGNotificationListener() {

        @Override
        public void notification(final int processId, final String channelName, final String payload) {
            
            restartDelay=0;
            Logger.debug(PostgresPubSubImpl.class,
                            () -> "recieved event: " + processId + ", " + channelName + ", " + payload);
            
            List<DotPubSubTopic> matchingTopics = topicMap.values().stream()
                            .filter(t -> t.getKey().toString().compareToIgnoreCase(channelName) == 0)
                            .collect(Collectors.toList());

            if (matchingTopics.isEmpty()) {
                return;
            }
            
            final DotPubSubEvent event = Try.of(() -> new DotPubSubEvent(payload))
                            .onFailure(e -> Logger.warn(PostgresPubSubImpl.class, e.getMessage(), e)).getOrNull();
            if (event == null) {
                return;
            }

            // save for testing
            lastEventIn = event;
            restartDelay=0;

            matchingTopics.forEach(t -> {
                t.notify(event);
                t.incrementReceivedCounters(event);
            });

        }

        @Override
        public void closed() {
            if (state.get() != RUNSTATE.STOPPED) {
                Logger.warn(this.getClass(), "PGNotificationListener connection closed, reconnecting");
                restart();
            }
        }
    };

    /**
     * Runs the SQL that starts the listening process and activates the PGNotificationListener
     */
    private void setUpConnection() {
        if (connection != null) {
            Logger.info(this.getClass(), () -> "PGNotificationListener already connected. Returning");
            return;
        }
        state.set(RUNSTATE.STARTED);

        Logger.info(this.getClass(), () -> "PGNotificationListener connecting to pub/sub...");
        try {

            connection = DriverManager.getConnection(attributes.get().getDbUrl()).unwrap(PGConnection.class);
            connection.addNotificationListener(listener);

        } catch (Exception e) {
            Logger.warnAndDebug(getClass(), e);
            if (state.get() != RUNSTATE.STOPPED) {
                restart();
            }
        }

    }


    private void subscribeToTopicSQL(String topic) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            Logger.info(this.getClass(), () -> " - LISTEN " + topic.toLowerCase());
            stmt.execute("LISTEN " + topic.toString().toLowerCase());
        }
    }
    
    private void unsubscribeToTopicSQL(String topic) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            Logger.info(this.getClass(), () -> " - UNLISTEN " + topic.toLowerCase());
            stmt.execute("UNLISTEN " + topic.toString().toLowerCase());
        }
    }
    
    
    
    
    private void listen() {
        try {
            setUpConnection();
            for (DotPubSubTopic topic : topicMap.values()) {
                subscribeToTopicSQL(topic.getKey().toString());
            }
        } catch (Exception e) {
            Logger.warnAndDebug(getClass(), e);
            if (state.get() != RUNSTATE.STOPPED) {
                restart();
            }
        }

    }
    
    
    
    
    

    /**
     * This will automatically restart the connection
     */
    public void restart() {
       
        Logger.warn(getClass(), "Restarting PGNotificationListener in " +restartDelay +" ms to retry postgres pub/sub connection");
        stop();
        restartDelay=Math.min(restartDelay+1000, 10000);
        Try.run(() -> Thread.sleep(restartDelay));
        listen();
    }

    /**
     * Stops the listener and connection
     */
    public void stop() {
        this.state.set(RUNSTATE.STOPPED);
        Try.run(() -> connection.close());
        connection = null;
    }

    
    /**
     * allow a user to override the DB server for PubSub Activity
     * Otherwise, we will just use the same DB
     * Format:
     * 
     *  jdbc:pgsql://{username}:{password}@{serverName}/{dbName}
     *  jdbc:pgsql://dotcms:dotcms@myDbServer.com/dotcms
     * 
     * @return
     */

    private PgNgDataSourceUrl getDatasourceAttributes() {
        String POSTGRES_PUBSUB_JDBC_URL = Config.getStringProperty("POSTGRES_PUBSUB_JDBC_URL",null);
        if(POSTGRES_PUBSUB_JDBC_URL!=null) {
            return new PgNgDataSourceUrl(POSTGRES_PUBSUB_JDBC_URL);
        }
        
        
        HikariDataSource hds = (HikariDataSource) DbConnectionFactory.getDataSource();
        return new PgNgDataSourceUrl(hds.getUsername(), hds.getPassword(), hds.getJdbcUrl());

    }


    @Override
    public DotPubSubProvider subscribe(DotPubSubTopic topic) {
        this.topicMap.put(topic.getKey(), topic);
        try {
            subscribeToTopicSQL(topic.getKey().toString());
        }
        catch(Exception e) {
            restart();
        }
        
        return this;
    }
    
    @Override
    public DotPubSubProvider unsubscribe(DotPubSubTopic topic) {
        this.topicMap.remove(topic.getKey());
        try {
            unsubscribeToTopicSQL(topic.getKey().toString());
        }
        catch(Exception e) {
            restart();
        }
        
        
        return this;
    }


    @Override
    public boolean publish(DotPubSubTopic topic, DotPubSubEvent eventIn) {

        final DotPubSubEvent eventOut = new DotPubSubEvent.Builder(eventIn).withOrigin(serverId).build();

        topic.incrementSentCounters(eventOut);


        Logger.debug(getClass(), () -> "sending  event:" + eventOut);
        try (Connection conn = DbConnectionFactory.getDataSource().getConnection()) {
            // postgres pubsub cannot send more than 8000 bytes
            if (eventOut.toString().getBytes().length > 8000) {
                throw new DotRuntimeException("Payload too large, must be under 8000b:" + eventOut.toString());
            }
            new DotConnect().setSQL("SELECT pg_notify(?,?)").addParam(topic.getKey()).addParam(eventOut.toString())
                            .loadResult(conn);

            lastEventOut = eventOut;
            return true;
        } catch (Exception e) {
            Logger.warnAndDebug(this.getClass(), "Unable to send pubsub:" + e.getMessage(), e);
            return false;
        }

    }






}