/*
 * Copyright (c) 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.oort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortObject<T> implements Oort.CometListener, ServerChannel.MessageListener, Iterable<OortObject.Info<T>>
{
    public static final String OORT_OBJECTS_CHANNEL = "/oort/objects";

    private final ConcurrentMap<String, Info<T>> infos = new ConcurrentHashMap<String, Info<T>>();
    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<Listener<T>>();
    protected final Logger logger;
    private final Oort oort;
    private final String name;
    private final Factory<T> factory;
    private final LocalSession sender;

    public OortObject(Oort oort, String name, Factory<T> factory)
    {
        this.oort = oort;
        this.name = name;
        this.factory = factory;
        logger = LoggerFactory.getLogger(getClass().getName() + "." + oort.getURL() + "." + name);
        setLocal(factory.newObject(null));
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        this.sender = bayeuxServer.newLocalSession(getClass().getSimpleName() + "." + name);
        this.sender.handshake();
        oort.addCometListener(this);
        bayeuxServer.createIfAbsent(OORT_OBJECTS_CHANNEL, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).addListener(this);
        oort.observeChannel(OORT_OBJECTS_CHANNEL);
    }

    public Oort getOort()
    {
        return oort;
    }

    public String getName()
    {
        return name;
    }

    public Factory<T> getFactory()
    {
        return factory;
    }

    public LocalSession getLocalSession()
    {
        return sender;
    }

    public void cometJoined(Event event)
    {
        // Nothing to do because it is too early to push local data:
        // the OortObject on the new Oort may not be setup yet
        logger.debug("Oort {} joined", event.getCometURL());
    }

    public void cometLeft(Event event)
    {
        logger.debug("Oort {} left", event.getCometURL());
        Info<T> info = infos.remove(event.getCometURL());
        logger.debug("Removed remote info {}", info);
        notifyRemoved(info);
    }

    public Iterator<Info<T>> iterator()
    {
        return infos.values().iterator();
    }

    public void addListener(Listener<T> listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener<T> listener)
    {
        listeners.remove(listener);
    }

    protected void notifyUpdated(Info<T> oldInfo, Info<T> newInfo)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onUpdated(oldInfo, newInfo);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    protected void notifyRemoved(Info<T> info)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onRemoved(info);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
    {
        Object data = message.getData();
        if (data instanceof Map)
            return onMessage((Map<String, Object>)data);

        if (data instanceof Object[])
            data = Arrays.asList((Object[])data);

        if (data instanceof List)
        {
            boolean result = true;
            for (Object element : (List<?>)data)
            {
                if (element instanceof Map)
                    result &= onMessage((Map<String, Object>)data);
            }
            return result;
        }
        return true;
    }

    private boolean onMessage(Map<String, Object> data)
    {
        boolean sameOort = oort.getURL().equals(data.get(Info.OORT_URL_FIELD));
        boolean sameName = getName().equals(data.get(Info.NAME_FIELD));
        if (!sameOort && sameName)
            onObject(data);
        return true;
    }

    protected void onObject(Map<String, Object> data)
    {
        Info<T> newInfo = new Info<T>(data);
        logger.debug("Received remote info {}", newInfo);

        // Default behavior is to replace atomically
        Info<T> oldInfo;
        String newOortURL = newInfo.getOortURL();
        boolean initial = Info.TYPE_FIELD_INITIAL_VALUE.equals(newInfo.get(Info.TYPE_FIELD));
        if (initial)
            // Make sure we don't overwrite existing data with initial data
            oldInfo = infos.putIfAbsent(newOortURL, newInfo);
        else
            oldInfo = infos.put(newOortURL, newInfo);

        if (!initial || oldInfo == null)
            notifyUpdated(oldInfo, newInfo);

        // If we did not have an info for the new Oort, then it's a
        // new OortObject and we need to push our own data to it.
        if (oldInfo == null)
        {
            Info<T> localInfo = getInfo(oort.getURL());
            localInfo.put(Info.TYPE_FIELD, Info.TYPE_FIELD_INITIAL_VALUE);
            logger.debug("Pushing (to {}) local info {}", newOortURL, localInfo);
            OortComet oortComet = oort.getComet(newOortURL);
            if (oortComet != null)
                oortComet.getChannel(OORT_OBJECTS_CHANNEL).publish(localInfo);
        }
    }

    public T getLocal()
    {
        return getRemote(oort.getURL());
    }

    public void setLocal(T local)
    {
        if (local == null)
            throw new NullPointerException();
        Info<T> info = new Info<T>();
        info.put(Info.OORT_URL_FIELD, oort.getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, local);
        logger.debug("Setting local info {}", info);
        infos.put(oort.getURL(), info);
    }

    public T getRemote(String oortURL)
    {
        Info<T> info = getInfo(oortURL);
        return info == null ? null : info.getObject();
    }

    protected Info<T> getInfo(String oortURL)
    {
        return infos.get(oortURL);
    }

    public T get(MergeStrategy<T> strategy)
    {
        return strategy.merge(infos.values());
    }

    public void share()
    {
        Info<T> info = getInfo(oort.getURL());
        if (info != null)
        {
            logger.debug("Sharing local info {}", info);
            BayeuxServer bayeuxServer = oort.getBayeuxServer();
            bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(sender, info, null);
        }
    }

    public interface Factory<E>
    {
        public E newObject(Object representation);
    }

    public static class MapFactory implements Factory<Map<String, Object>>
    {
        @SuppressWarnings("unchecked")
        public Map<String, Object> newObject(Object representation)
        {
            if (representation == null)
                return new HashMap<String, Object>();
            if (representation instanceof Map)
                return (Map<String, Object>)representation;
            throw new IllegalArgumentException();
        }
    }

    public static class ConcurrentListFactory<E> implements Factory<List<E>>
    {
        @SuppressWarnings("unchecked")
        public List<E> newObject(Object representation)
        {
            if (representation == null)
                return new CopyOnWriteArrayList<E>();
            if (representation instanceof CopyOnWriteArrayList)
                return (List<E>)representation;
            if (representation instanceof List)
                return new CopyOnWriteArrayList<E>((List<E>)representation);
            if (representation instanceof Object[])
            {
                List<E> result = new CopyOnWriteArrayList<E>();
                for (Object element : (Object[])representation)
                    result.add((E)element);
                return result;
            }
            throw new IllegalArgumentException();
        }
    }

    public static class ConcurrentMapFactory<K, V> implements Factory<ConcurrentMap<K, V>>
    {
        @SuppressWarnings("unchecked")
        public ConcurrentMap<K, V> newObject(Object representation)
        {
            if (representation == null)
                return new ConcurrentHashMap<K, V>();
            if (representation instanceof ConcurrentMap)
                return (ConcurrentMap<K, V>)representation;
            if (representation instanceof Map)
                return new ConcurrentHashMap<K, V>((Map<K, V>)representation);
            throw new IllegalArgumentException();
        }
    }

    public static class Info<E> extends HashMap<String, Object>
    {
        public static final String OORT_URL_FIELD = "oortURL";
        public static final String NAME_FIELD = "name";
        public static final String OBJECT_FIELD = "object";
        public static final String TYPE_FIELD = "type";
        public static final String TYPE_FIELD_INITIAL_VALUE = "initial";
        public static final String ACTION_FIELD = "action";

        public Info()
        {
        }

        public Info(Map<? extends String, ?> map)
        {
            super(map);
        }

        public String getOortURL()
        {
            return (String)get(OORT_URL_FIELD);
        }

        public String getName()
        {
            return (String)get(NAME_FIELD);
        }

        @SuppressWarnings("unchecked")
        public E getObject()
        {
            return (E)get(OBJECT_FIELD);
        }

        @Override
        public String toString()
        {
            E object = getObject();
            String objectString = object instanceof Object[] ? Arrays.toString((Object[])object) : String.valueOf(object);
            return String.format("'%s' (from %s): %s", getName(), getOortURL(), objectString);
        }
    }

    public interface MergeStrategy<E>
    {
        public E merge(Collection<Info<E>> values);
    }

    public static class UnionMergeStrategyList<E> implements MergeStrategy<List<E>>
    {
        public List<E> merge(Collection<Info<List<E>>> values)
        {
            List<E> result = new ArrayList<E>();
            for (Info<List<E>> value : values)
                result.addAll(value.getObject());
            return result;
        }
    }

    public static class UnionMergeStrategyMap<K, V> implements MergeStrategy<Map<K, V>>
    {
        public Map<K, V> merge(Collection<Info<Map<K, V>>> values)
        {
            Map<K, V> result = new HashMap<K, V>();
            for (Info<Map<K, V>> value : values)
                result.putAll(value.getObject());
            return result;
        }
    }

    public interface Listener<T> extends EventListener
    {
        public void onUpdated(Info<T> oldInfo, Info<T> newInfo);

        public void onRemoved(Info<T> info);

        public static class Adapter<T> implements Listener<T>
        {
            public void onUpdated(Info<T> oldInfo, Info<T> newInfo)
            {
            }

            public void onRemoved(Info<T> info)
            {
            }
        }
    }
}
