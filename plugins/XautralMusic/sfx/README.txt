Pon SFX aquí (.ogg/.wav/.mp3... -> ogg vorbis)
Ej: sfx/grunt_kill.ogg => xautral:sfx.grunt_kill
Crea eventos .xme en esta carpeta:
  execute when [action:die] run playsound xautral:sfx.grunt_kill 1 [players:all] [pos:-1,-1,-1]
Acciones: die, kill, join, quit, respawn, sneak, sprint, jump, damage, interact
Players: all/@a/@p/@s  Pos: -1,-1,-1 (=en jugador) o x,y,z o @a/@p
Luego /xm reload
