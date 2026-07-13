package com.subwave.radio.player

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/**
 * Reports whether the car currently requires distraction-optimized UI (i.e.
 * the vehicle is in motion), so callers can gate free-text input like the
 * server address field to parked-only use.
 *
 * References android.car directly, so callers must only construct this
 * after confirming FEATURE_AUTOMOTIVE is present - the class is never
 * touched, and its classes never loaded, on a regular phone.
 */
class CarDrivingState(context: Context, private val onChanged: (Boolean) -> Unit) {

    private var car: Car? = null
    private var restrictionsManager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions: CarUxRestrictions ->
        onChanged(restrictions.isRequiresDistractionOptimization)
    }

    init {
        car = Car.createCar(context.applicationContext)
        restrictionsManager =
            car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        restrictionsManager?.registerListener(listener)
        restrictionsManager?.currentCarUxRestrictions?.let {
            onChanged(it.isRequiresDistractionOptimization)
        }
    }

    fun release() {
        restrictionsManager?.unregisterListener()
        car?.disconnect()
    }
}
