package io.digitalmagic

package object coproduct {
  type TNil <: TList
  type TCons[H, T <: TList] <: TList
  type TNilK <: TListK
  type TConsK[H[_], T <: TListK] <: TListK
}
