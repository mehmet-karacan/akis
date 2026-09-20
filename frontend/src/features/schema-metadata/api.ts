import { apiRequest } from '../../core/api/client'

export interface SemaTanimi {
  uuid: string
  ad: string
  aciklama?: string | null
}

export interface TabloTanimi {
  uuid: string
  semaTanimiUuid: string
  ad: string
  aciklama?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

export interface KolonTanimi {
  uuid: string
  tabloTanimiUuid: string
  siraNo: number
  ad: string
  aciklama?: string | null
  veriTipi: string
  uzunluk?: number | null
  zorunluMu: boolean
  varsayilanDeger?: string | null
}

export interface KisitTanimi {
  uuid: string
  tabloTanimiUuid: string
  ad: string
  aciklama?: string | null
  tur: 'PRIMARY_KEY' | 'UNIQUE' | 'CHECK' | 'FOREIGN_KEY'
  checkIfadesi?: string | null
}

export interface IndeksTanimi {
  uuid: string
  tabloTanimiUuid: string
  ad: string
  aciklama?: string | null
  tur: string
  benzersizMi: boolean
}

export interface IliskiTanimi {
  uuid: string
  kaynakKisitAdi: string
  hedefTabloTanimiUuid: string
  hedefTabloAdi: string
  hedefKisitAdi: string
  silmeKurali: string
  guncellemeKurali: string
}

export interface SiraTanimi {
  uuid: string
  semaTanimiUuid: string
  ad: string
  aciklama?: string | null
  baslangicDegeri: number
  artisMiktari: number
  minDeger?: number | null
  maxDeger?: number | null
  donguselMi: boolean
}

const base = '/api/v1/sema-metadata'
const get = <T>(path: string) => apiRequest<T>(path)

export const schemaMetadataApi = {
  listSemalar: () => get<SemaTanimi[]>(`${base}/semalar`),
  listTablolar: (semaUuid: string) => get<TabloTanimi[]>(`${base}/semalar/${semaUuid}/tablolar`),
  listSequences: (semaUuid: string) => get<SiraTanimi[]>(`${base}/semalar/${semaUuid}/sequences`),
  listKolonlar: (tabloUuid: string) => get<KolonTanimi[]>(`${base}/tablolar/${tabloUuid}/kolonlar`),
  listKisitlar: (tabloUuid: string) => get<KisitTanimi[]>(`${base}/tablolar/${tabloUuid}/kisitlar`),
  listIndeksler: (tabloUuid: string) => get<IndeksTanimi[]>(`${base}/tablolar/${tabloUuid}/indeksler`),
  listIliskiler: (tabloUuid: string) => get<IliskiTanimi[]>(`${base}/tablolar/${tabloUuid}/iliskiler`),
}
