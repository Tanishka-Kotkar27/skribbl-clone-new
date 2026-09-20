/**
 * Spring Data JPA repositories for the game tables.
 *
 * <p>All five are plain {@code JpaRepository} interfaces with derived query
 * methods; the only hand-written query is the category listing. Deliberately
 * absent is a random-word query — see {@link com.skribbl.repository.WordRepository}
 * for why word selection happens in memory instead.
 */
package com.skribbl.repository;
