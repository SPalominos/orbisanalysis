package org.orbisgis.osm.utils

import java.text.SimpleDateFormat
import java.time.ZoneId

/**
 * Status of the overpass server.
 *
 * @author Sylvain PALOMINOS (UBS LABSTICC 2019)
 */
class OverpassStatus {

    /** String used to parse the slot {@link String} representation */
    private static final CONNECT_AS = "Connected as: "
    /** String used to parse the slot {@link String} representation */
    private static final CURRENT_TIME = "Current time: "
    /** String used to parse the slot {@link String} representation */
    private static final RATE_LIMIT = "Rate limit: "
    /** String used to parse the slot {@link String} representation */
    private static final SLOT_AVAILABLE = " slots available now."
    /** String used to parse the slot {@link String} representation */
    private static final RUNNING_QUERIES = "Currently running queries (pid, space limit, time limit, start time):"

    /** {@link SimpleDateFormat} used to parse dates. */
    private format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    private local = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")

    /** Connection id */
    long connectionId
    /** {@link Date} of the status */
    Date time
    /** Maximum number of slots */
    int slotLimit
    /** Number of available slots */
    int slotAvailable = 0
    /** Lis of locked slots */
    List<Slot> slots = new ArrayList<>()
    /** List of running queries */
    List<RunningQuery> runningQueries = new ArrayList<>()

    /**
     * Main constructor
     * @param test String representation of the server status.
     */
    OverpassStatus(String test){
        format.setTimeZone(TimeZone.getTimeZone("Etc/GMT+0"))
        local.setTimeZone(TimeZone.getTimeZone(ZoneId.systemDefault()))
        def array = test.split("\n")
        connectionId = Long.decode(array[0]-CONNECT_AS)
        time = format.parse(array[1]-CURRENT_TIME)
        slotLimit = Integer.decode(array[2]-RATE_LIMIT)
        def i = 3
        if(array[i].contains(SLOT_AVAILABLE)) {
            slotAvailable = Integer.decode(array[i] - SLOT_AVAILABLE)
            i++
        }
        if(!array[i].contains(SLOT_AVAILABLE)) {
            while (array[i] != RUNNING_QUERIES) {
                slots << new Slot(array[i])
                i++
            }
        }
        i++
        while(array.length > i){
            runningQueries << new RunningQuery(array[i])
            i++
        }
    }

    /**
     * Return the time in seconds to wait before the next slot is unlocked.
     * If all slots are occupied by queries, return -1,  {@see }
     * @param current
     * @return
     */
    def getSlotWaitTime(){
        if(slotAvailable > 0) return 0
        def time = slots.isEmpty() ? -1 : Long.MAX_VALUE
        for(Slot slot : slots){
            time = Math.min(time, slot.waitSeconds)
        }
        return time
    }

    /**
     * Return the minimal timeout in seconds of the running queries.
     * @return Running query timeout.
     */
    def getQueryWaitTime(){
        def time = runningQueries.isEmpty() ? -1 : Long.MAX_VALUE
        for(RunningQuery runningQuery : runningQueries){
            time = Math.min(time, runningQuery.waitSeconds)
        }
        return time
    }

    /**
     * Wait until a slot is available with the given timeout.
     * If there is no locked slot, return false.
     * @param timeout Timeout for the waiting.
     * @return True if after waiting a slot is available, false if the timeout is under the wait time or if after
     * waiting there is no available slot or if all the slots are running queries.
     */
    boolean waitForSlot(int timeout){
        def waitTime = getSlotWaitTime()
        def to = timeout

        if(waitTime == 0) return true
        if(waitTime == -1) return false
        if(to < waitTime) return false

        Thread.sleep((waitTime + 1) * 1000)

        return true
    }



    /**
     * Wait until a running query ends.
     * @param timeout Timeout for the waiting.
     * @return True if after waiting a query has ended, false otherwise
     */
    boolean waitForQueryEnd(int timeout){
        def queryTime = getQueryWaitTime()
        def to = timeout

        if(queryTime == -1) return true
        if( to < queryTime) return false

        Thread.sleep((queryTime + 1) * 1000)

        return true
    }

    @Override
    String toString(){
        def str = "$CONNECT_AS$connectionId\n"
        str += "$CURRENT_TIME${local.format(time)}\n"
        str += "$RATE_LIMIT$slotLimit\n"
        str += "$slotAvailable$SLOT_AVAILABLE\n"
        if(slots) str += "${slots.join("\n")}\n"
        str += "$RUNNING_QUERIES\n"
        if(runningQueries) str += "${runningQueries.join("\n")}"

        return str
    }
}