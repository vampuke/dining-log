package top.alanvampuke.dininglog.core

@JvmInline
value class Rating private constructor(val tenths: Int) {
    init { require(tenths in 10..50) { "Rating must be between 10 and 50 tenths" } }
    val display: String get() = "${tenths / 10}.${tenths % 10}"
    override fun toString(): String = display
    companion object {
        fun fromTenths(tenths: Int): Rating = Rating(tenths)
    }
}
