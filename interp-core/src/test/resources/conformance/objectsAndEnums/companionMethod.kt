class Holder {
    companion object {
        fun make(): String = "OK"
    }
}
fun box(): String = Holder.make()
