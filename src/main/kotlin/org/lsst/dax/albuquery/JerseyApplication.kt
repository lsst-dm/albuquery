package org.lsst.dax.albuquery

import java.util.concurrent.Executors
import javax.ws.rs.core.Application

val EXECUTOR = Executors.newCachedThreadPool()

class JerseyApplication: Application() {

    override fun getSingletons(): MutableSet<Any> {
        return mutableSetOf(AsyncResource())
    }


}