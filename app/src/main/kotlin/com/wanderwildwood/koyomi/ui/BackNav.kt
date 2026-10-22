package com.wanderwildwood.koyomi.ui

import com.wanderwildwood.koyomi.data.View

/** Where Back goes from [view] when nothing is laid over it: the Month view, or null to leave the app. */
fun backTarget(view: View): View? = if (view == View.MONTH) null else View.MONTH
