package focusedCrawler.link;

import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

import focusedCrawler.link.frontier.LinkRelevance;

/**
 * Makes sure that links are selected respecting politeness constraints: a link from the same host
 * is never selected twice within a given minimum access time interval. That means that the natural
 * order (based on link relevance) is modified so that links are selected based on last time that
 * the host was last accessed in order to respect the minimum access time limit.
 * 
 * @author aeciosantos
 *
 */
public class PolitenessScheduler {
    
    private static class DomainNode {
        
        final String domainName;
        final Deque<LinkRelevance> links;
        volatile long lastAccessTime;
        
        public DomainNode(String domainName, long lastAccessTime) {
            this.domainName = domainName;
            this.links = new LinkedList<>();
            this.lastAccessTime = lastAccessTime;
        }
        
    }
    
    private final PriorityQueue<DomainNode> domainsQueue;
    private final PriorityQueue<DomainNode> emptyDomainsQueue;
    private final Map<String, DomainNode> domains;
    private final long minimumAccessTime;
    private final int maxLinksInScheduler;
    
    private AtomicInteger numberOfLinks = new AtomicInteger(0);

    public PolitenessScheduler(int minimumAccessTimeInterval, int maxLinksInScheduler) {
        this.minimumAccessTime = minimumAccessTimeInterval;
        this.maxLinksInScheduler = maxLinksInScheduler;
        this.domains = new HashMap<>();
        this.emptyDomainsQueue = createDomainPriorityQueue();
        this.domainsQueue = createDomainPriorityQueue();
    }

    private PriorityQueue<DomainNode> createDomainPriorityQueue() {
        int initialCapacity = 10;
        return new PriorityQueue<DomainNode>(initialCapacity, new Comparator<DomainNode>() {
            @Override
            public int compare(DomainNode o1, DomainNode o2) {
                return Long.compare(o1.lastAccessTime, o2.lastAccessTime);
            }
        });
    }
    
    public boolean addLink(LinkRelevance link) {

        removeExpiredNodes();
        
        if(numberOfLinks() >= maxLinksInScheduler) {
            return false; // ignore link
        }
        numberOfLinks.incrementAndGet();
        
        String domainName = link.getTopLevelDomainName();
        
        synchronized(this) {
            DomainNode domainNode = domains.get(domainName);
            if(domainNode == null) {
                domainNode = new DomainNode(domainName, 0l);
                domains.put(domainName, domainNode);
            }
            
            if(domainNode.links.isEmpty()) {
                emptyDomainsQueue.remove(domainNode);
                domainsQueue.add(domainNode);
            }
            
            domainNode.links.addLast(link);
        }
        
        return true;
    }

    private synchronized void removeExpiredNodes() {
        while(true) {
            DomainNode node = emptyDomainsQueue.peek();
            if(node == null) {
                break;
            }
            
            long expirationTime = node.lastAccessTime + minimumAccessTime;
            if(System.currentTimeMillis() > expirationTime) {
                emptyDomainsQueue.poll();
                domains.remove(node.domainName);
            } else {
                break;
            }
        }
    }

    public LinkRelevance nextLink() {
        LinkRelevance linkRelevance;
        
        synchronized (this) {

            DomainNode domainNode = domainsQueue.peek();
            if (domainNode == null) {
                // no domains available to be crawled
                return null;
            }

            long now = System.currentTimeMillis();
            long timeSinceLastAccess = now - domainNode.lastAccessTime;
            if (timeSinceLastAccess < minimumAccessTime) {
                // the domain with longest access time cannot be crawled right now
                return null;
            }
            
            domainsQueue.poll(); 
            linkRelevance = domainNode.links.removeFirst();
            domainNode.lastAccessTime = System.currentTimeMillis();
            if (domainNode.links.isEmpty()) {
                emptyDomainsQueue.add(domainNode);
            } else {
                domainsQueue.add(domainNode);
            }

        }

        numberOfLinks.decrementAndGet();

        return linkRelevance;
    }
    
    public int numberOfNonExpiredDomains() {
        removeExpiredNodes();
        return domains.size();
    }
    
    public int numberOfAvailableDomains() {
        int available = 0;
        for(DomainNode node : domainsQueue) {
            if(isAvailable(node)){
                available++;
            }
        }
        return available;
    }

    public int numberOfEmptyDomains() {
        return emptyDomainsQueue.size();
    }

    public int numberOfLinks() {
        return numberOfLinks.get();
    }

    public boolean hasPendingLinks() {
        return numberOfLinks() > 0;
    }

    public boolean hasLinksAvailable() {
        // pick domain with longest access time 
        DomainNode domainNode = domainsQueue.peek();
        if(domainNode == null) {
            return false;
        }
        return isAvailable(domainNode);
    }

    private boolean isAvailable(DomainNode domainNode) {
        long now = System.currentTimeMillis();
        long timeSinceLastAccess = now - domainNode.lastAccessTime;
        if(timeSinceLastAccess < minimumAccessTime) {
            return false;
        }
        return true;
    }

    public synchronized void clear() {
        Iterator<Entry<String, DomainNode>> it = domains.entrySet().iterator();
        while(it.hasNext()) {
            DomainNode node = it.next().getValue();
            numberOfLinks.addAndGet(-node.links.size()); // adds negative value
            node.links.clear();
        }
        while(true) {
            DomainNode node = domainsQueue.poll();
            if(node == null) {
                break;
            }
            emptyDomainsQueue.add(node);
        }
    }

    public boolean canDownloadNow(LinkRelevance link) {
        DomainNode domain = domains.get(link.getTopLevelDomainName());
        if(domain == null) {
            return true;
        } else {
            return isAvailable(domain);
        }
    }
    
}
