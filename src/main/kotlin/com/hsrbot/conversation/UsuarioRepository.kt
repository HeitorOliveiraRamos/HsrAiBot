package com.hsrbot.conversation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UsuarioRepository : JpaRepository<Usuario, Long> {

    fun findByUsuarioId(usuarioId: String): Usuario?
}
