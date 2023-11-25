/*
 * Copyright (C) 2022-2023 Tipz Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tipz.viola.broha.database.icons

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface IconHashDao {
    @Query("SELECT * FROM iconHash WHERE id LIKE :id LIMIT 1")
    fun findById(id: Int): IconHash

    @Query("SELECT * FROM iconHash WHERE iconHash LIKE :hash LIMIT 1")
    fun findByHash(hash: Int): IconHash

    @get:Query("SELECT * FROM iconHash LIMIT 1")
    val isEmpty: List<IconHash>

    @Query("SELECT * FROM iconHash ORDER BY id DESC LIMIT 1")
    fun lastIcon(): IconHash

    @Insert
    fun insertAll(vararg iconHash: IconHash)
}