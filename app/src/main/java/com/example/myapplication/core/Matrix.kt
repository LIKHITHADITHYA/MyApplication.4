package com.example.myapplication.core

class Matrix(private val rows: Int, private val cols: Int) {
    private val data: DoubleArray = DoubleArray(rows * cols)

    operator fun get(row: Int, col: Int): Double {
        return data[row * cols + col]
    }

    operator fun set(row: Int, col: Int, value: Double) {
        data[row * cols + col] = value
    }

    operator fun times(other: Matrix): Matrix {
        if (cols != other.rows) throw IllegalArgumentException("Matrix dimensions are not compatible for multiplication.")
        val result = Matrix(rows, other.cols)
        for (i in 0 until rows) {
            for (j in 0 until other.cols) {
                var sum = 0.0
                for (k in 0 until cols) {
                    sum += this[i, k] * other[k, j]
                }
                result[i, j] = sum
            }
        }
        return result
    }
    
    fun transpose(): Matrix {
        val result = Matrix(cols, rows)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j, i] = this[i, j]
            }
        }
        return result
    }

    operator fun plus(other: Matrix): Matrix {
        if (rows != other.rows || cols != other.cols) throw IllegalArgumentException("Matrix dimensions must be the same for addition.")
        val result = Matrix(rows, cols)
        for (i in 0 until data.size) {
            result.data[i] = data[i] + other.data[i]
        }
        return result
    }

    operator fun minus(other: Matrix): Matrix {
        if (rows != other.rows || cols != other.cols) throw IllegalArgumentException("Matrix dimensions must be the same for subtraction.")
        val result = Matrix(rows, cols)
        for (i in 0 until data.size) {
            result.data[i] = data[i] - other.data[i]
        }
        return result
    }
    
    // Simplified inverse for a 2x2 matrix, which is common in EKFs for innovation covariance
    fun inverse(): Matrix {
        if (rows != cols) throw UnsupportedOperationException("Can only invert square matrices.")
        if (rows != 2) throw UnsupportedOperationException("This simplified inverse only supports 2x2 matrices.")

        val det = this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]
        if (det == 0.0) throw UnsupportedOperationException("Matrix is singular and cannot be inverted.")

        val result = Matrix(2, 2)
        result[0, 0] = this[1, 1] / det
        result[0, 1] = -this[0, 1] / det
        result[1, 0] = -this[1, 0] / det
        result[1, 1] = this[0, 0] / det
        return result
    }

    companion object {
        fun identity(size: Int): Matrix {
            val result = Matrix(size, size)
            for (i in 0 until size) {
                result[i, i] = 1.0
            }
            return result
        }
    }
}
